package au.com.dingwall.mark.bitbrush.migration;

import au.com.dingwall.mark.bitbrush.BitbrushApplication;
import au.com.dingwall.mark.bitbrush.dto.UserCreateRequest;
import au.com.dingwall.mark.bitbrush.dto.UserIdentityResponse;
import au.com.dingwall.mark.bitbrush.service.PinCredentialService;
import au.com.dingwall.mark.bitbrush.service.PinCredentialCodec;
import au.com.dingwall.mark.bitbrush.service.TurnstileService;
import au.com.dingwall.mark.bitbrush.repository.UserRepository;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.slf4j.LoggerFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.*;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.sql.Connection;
import java.util.concurrent.*;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

@ExtendWith(OutputCaptureExtension.class)
class LegacyIdentityMigrationTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final String LEGACY_A = "10000000-0000-4000-8000-000000000001";
    private static final String LEGACY_B = "20000000-0000-4000-8000-000000000002";
    private static final byte[] PEPPER_BYTES = "migration-test-unique-pepper-32-bytes-2026".getBytes(StandardCharsets.UTF_8);
    private static final String PEPPER = Base64.getEncoder().encodeToString(PEPPER_BYTES);
    private String schema;
    private JdbcTemplate jdbc;

    @BeforeAll
    static void startPostgres() {
        boolean available = DockerClientFactory.instance().isDockerAvailable();
        if ("true".equalsIgnoreCase(System.getenv("CI"))) {
            assertTrue(available, "CI requires Docker: migration tests must execute");
        }
        Assumptions.assumeTrue(available, "Docker is unavailable locally");
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        if (POSTGRES.isRunning()) POSTGRES.stop();
    }

    @BeforeEach
    void createLegacySchema() {
        schema = "legacy_" + UUID.randomUUID().toString().replace("-", "");
        configuration().target("1").load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(databaseUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @AfterEach
    void cleanSchema() {
        if (schema != null) configuration().cleanDisabled(false).load().clean();
    }

    @Test
    void rotatesLegacyBearersAndPreservesEveryPixelRow() {
        seedLegacyUsersAndPixels();
        List<Map<String, Object>> before = pixelRows("author_uuid");

        var result = configuration().placeholders(Map.of("pin-pepper", PEPPER)).load().migrate();

        assertEquals(3, result.migrationsExecuted, "V2, V3 and V4 must execute");
        List<Map<String, Object>> users = jdbc.queryForList("SELECT * FROM users ORDER BY username");
        assertEquals(2, users.size());
        var codec = new PinCredentialCodec(PEPPER_BYTES, 19456, 2, 1, 32);
        for (int index = 0; index < users.size(); index++) {
            Map<String, Object> user = users.get(index);
            String oldUuid = List.of(LEGACY_A, LEGACY_B).get(index);
            String uuid = (String) user.get("uuid");
            assertEquals(oldUuid, user.get("author_id"));
            assertTrue(UUID.fromString(uuid).toString().equals(uuid), "Private UUID must be canonical");
            assertFalse(List.of(LEGACY_A, LEGACY_B).contains(uuid), "Legacy public IDs cannot remain bearers");
            assertEquals(true, user.get("pin_backfilled"));
            String hash = (String) user.get("pin_hash");
            assertTrue(hash.startsWith("$argon2id$v=19$m=19456,t=2,p=1$"), "Migration must use production Argon2 parameters");
            assertTrue(codec.verify(codec.canonicalize(codec.deriveLegacyPin(oldUuid)), hash), "Legacy PIN must verify");
        }
        assertNotEquals(users.get(0).get("uuid"), users.get(1).get("uuid"));
        assertEquals(before, pixelRows("author_id"), "Pixel values and physical tuples must remain untouched");
    }

    @Test
    void enforcesNativePostgresIdentityConstraintsAndNewUserDefaults() {
        seedLegacyUsersAndPixels();
        migrate();
        var names = jdbc.queryForList("""
            SELECT conname FROM pg_constraint WHERE conrelid = 'users'::regclass
            AND contype IN ('p', 'u') ORDER BY conname
            """, String.class);
        assertEquals(List.of("pk_users_uuid", "uk_users_author_id", "uk_users_username"), names);
        var alice = jdbc.queryForMap("SELECT * FROM users WHERE username = 'Alice'");
        String sql = "INSERT INTO users(uuid, username, author_id, pin_hash, pin_backfilled) VALUES (?, ?, ?, ?, ?)";
        assertConstraint("pk_users_uuid", sql, alice.get("uuid"), "Other", "author_other", "hash", false);
        assertConstraint("uk_users_username", sql, UUID.randomUUID().toString(), "Alice", "author_other", "hash", false);
        assertConstraint("uk_users_author_id", sql, UUID.randomUUID().toString(), "Other", LEGACY_A, "hash", false);
        for (int missing = 0; missing < 5; missing++) {
            Object[] values = {UUID.randomUUID().toString(), "Other", "author_other", "hash", false};
            values[missing] = null;
            var error = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql, values));
            assertTrue(error.getMostSpecificCause() instanceof java.sql.SQLException cause
                && "23502".equals(cause.getSQLState()), "Each identity column must reject NULL");
        }
        jdbc.update("INSERT INTO users(uuid, username, author_id, pin_hash) VALUES (?, 'New', 'author_new', 'hash')",
            UUID.randomUUID().toString());
        assertEquals(false, jdbc.queryForObject("SELECT pin_backfilled FROM users WHERE username = 'New'", Boolean.class));
    }

    @Test
    void retriesUuidCollisionsAgainstBothPrivateAndPublicColumns() {
        seedLegacyUsersAndPixels();
        UUID oldA = UUID.fromString(LEGACY_A);
        UUID oldB = UUID.fromString(LEGACY_B);
        UUID freeA = UUID.fromString("30000000-0000-4000-8000-000000000003");
        UUID freeB = UUID.fromString("40000000-0000-4000-8000-000000000004");
        try (var uuids = mockStatic(UUID.class, CALLS_REAL_METHODS)) {
            uuids.when(UUID::randomUUID).thenReturn(oldB, oldA, freeA, freeA, oldA, freeB);
            migrate();
        }
        assertTrue(freeA.toString().equals(jdbc.queryForObject("SELECT uuid FROM users WHERE username = 'Alice'", String.class)),
            "A collision with an unprocessed legacy private UUID must be retried");
        assertTrue(freeB.toString().equals(jdbc.queryForObject("SELECT uuid FROM users WHERE username = 'Bob'", String.class)),
            "Collisions with rotated private UUIDs and already-public legacy IDs must be retried");
    }

    @Test
    void repeatFlywayRunPreservesRotatedIdsAndSaltedCredentials() {
        seedLegacyUsersAndPixels();
        migrate();
        var before = jdbc.queryForList("SELECT * FROM users ORDER BY username");
        assertEquals(0, configuration().placeholders(Map.of("pin-pepper", PEPPER)).load().migrate().migrationsExecuted);
        assertTrue(before.equals(jdbc.queryForList("SELECT * FROM users ORDER BY username")),
            "A retry must preserve private UUIDs and existing salted hashes");
    }

    @Test
    void interruptedBackfillRollsBackAllUserChangesAndCanRetry() {
        seedLegacyUsersAndPixels();
        UUID firstRotation = UUID.fromString("30000000-0000-4000-8000-000000000003");
        try (var uuids = mockStatic(UUID.class, CALLS_REAL_METHODS)) {
            uuids.when(UUID::randomUUID).thenReturn(firstRotation)
                .thenThrow(new IllegalStateException("Simulated interruption after first identity update"));
            assertThrows(org.flywaydb.core.api.FlywayException.class, this::migrate);
        }
        assertEquals(List.of(LEGACY_A, LEGACY_B), jdbc.queryForList("SELECT uuid FROM users ORDER BY username", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM users WHERE author_id IS NULL AND pin_hash IS NULL AND pin_backfilled IS NULL", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version IN ('3', '4')", Integer.class));
        assertEquals(2, configuration().placeholders(Map.of("pin-pepper", PEPPER)).load().migrate().migrationsExecuted);
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM users WHERE author_id IS NOT NULL AND pin_hash IS NOT NULL AND pin_backfilled", Integer.class));
    }

    @Test
    void migratesAnEmptyLegacyDatabase() {
        assertEquals(3, configuration().placeholders(Map.of("pin-pepper", PEPPER)).load().migrate().migrationsExecuted);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM users", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pixels", Integer.class));
        assertEquals("4", configuration().placeholders(Map.of("pin-pepper", PEPPER)).load().info().current().getVersion().toString());
    }

    static Stream<Map<String, String>> invalidPeppers() {
        return Stream.of(Map.of(), Map.of("pin-pepper", "invalid-base64-marker!"),
            Map.of("pin-pepper", Base64.getEncoder().encodeToString(new byte[31])));
    }

    @ParameterizedTest(name = "invalid pepper case {index}")
    @MethodSource("invalidPeppers")
    void invalidPepperStopsBeforeReadingRowsAndBeforeV4(Map<String, String> placeholders, CapturedOutput output) throws Exception {
        seedLegacyUsersAndPixels();
        var before = jdbc.queryForList("SELECT uuid, username FROM users ORDER BY username");
        var pixelsBefore = pixelRows("author_uuid");
        assertThrows(org.flywaydb.core.api.FlywayException.class,
            () -> configuration().placeholders(placeholders).load().migrate());
        assertEquals(before, jdbc.queryForList("SELECT uuid, username FROM users ORDER BY username"));
        assertEquals(pixelsBefore, pixelRows("author_id"));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM users WHERE author_id IS NULL AND pin_hash IS NULL AND pin_backfilled IS NULL", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version IN ('3', '4')", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid = 'users'::regclass AND conname = 'uk_users_author_id'", Integer.class));

        // Rollback alone could hide reads or writes; enforce the pre-JDBC validation boundary as well.
        Context context = mock(Context.class);
        Connection connection = mock(Connection.class);
        when(context.getConfiguration()).thenReturn(configuration().placeholders(placeholders));
        when(context.getConnection()).thenReturn(connection);
        JavaMigration migration = (JavaMigration) Class.forName("db.migration.V3__Backfill_user_pin_credentials").getConstructor().newInstance();
        assertThrows(IllegalArgumentException.class, () -> migration.migrate(context));
        verifyNoInteractions(connection);
        verify(context, never()).getConnection();
        for (String marker : placeholders.values()) {
            assertFalse(output.getAll().contains(marker), "Failed migration logs exposed the invalid pepper marker");
        }
    }

    @Test
    void legacyPinHasIndependentStablePepperAndDomainVectors() {
        var codec = codec(PEPPER_BYTES);
        // Fixtures independently calculated using Node's HMAC-SHA256, not this codec.
        assertTrue("9912".equals(codec.deriveLegacyPin(LEGACY_A)), "Stable legacy PIN vector changed");
        assertTrue("3006".equals(codec.deriveLegacyPin(LEGACY_B)), "Public author ID must affect derivation");
        byte[] changedPepper = "migration-test-unique-pepper-32-bytes-2026!".getBytes(StandardCharsets.UTF_8);
        assertTrue("0825".equals(codec(changedPepper).deriveLegacyPin(LEGACY_A)), "Pepper must affect derivation");
        assertFalse("1782".equals(codec.deriveLegacyPin(LEGACY_A)), "Credential and legacy PIN HMAC domains must differ");
    }

    @Test
    void legacyPinRejectsBiasedDigestValuesAndRetriesWithADomainBoundCounter() throws Exception {
        Mac mac = mock(Mac.class);
        // 2^32 - 7296 is the first biased value. 2^32 - 7297 must still produce 9999.
        byte[] boundaryDigest = ByteBuffer.allocate(12).putInt(-1).putInt(-7296).putInt(-7297).array();
        byte[] rejectedDigest = new byte[32];
        Arrays.fill(rejectedDigest, (byte) 0xff);
        when(mac.doFinal()).thenReturn(boundaryDigest, rejectedDigest, ByteBuffer.allocate(4).putInt(7).array());
        var inputs = new java.util.ArrayList<byte[]>();
        doAnswer(invocation -> { inputs.add(((byte[]) invocation.getArgument(0)).clone()); return null; })
            .when(mac).update(any(byte[].class));
        try (var crypto = mockStatic(Mac.class)) {
            crypto.when(() -> Mac.getInstance("HmacSHA256")).thenReturn(mac);
            assertTrue("9999".equals(codec(PEPPER_BYTES).deriveLegacyPin(LEGACY_A)), "Biased values must be discarded");
            inputs.clear();
            assertTrue("0007".equals(codec(PEPPER_BYTES).deriveLegacyPin(LEGACY_A)), "Exhausted digests must derive another block");
        }
        assertEquals(5, inputs.size());
        assertArrayEquals("bitbrush-legacy-pin-v1\0".getBytes(StandardCharsets.US_ASCII), inputs.get(0));
        assertArrayEquals(LEGACY_A.getBytes(StandardCharsets.UTF_8), inputs.get(1));
        assertArrayEquals(inputs.get(0), inputs.get(2));
        assertArrayEquals(inputs.get(1), inputs.get(3));
        assertArrayEquals(new byte[]{0, 0, 0, 1}, inputs.get(4));
        verify(mac, times(3)).init(new SecretKeySpec(PEPPER_BYTES, "HmacSHA256"));
    }

    @Test
    void migratedApplicationRejectsPublicBearersAndRecoversPrivateIdentityWithoutSecretLogs(CapturedOutput output) throws Exception {
        seedLegacyUsersAndPixels();
        Logger flywayLogger = (Logger) LoggerFactory.getLogger("org.flywaydb");
        Level previousLevel = flywayLogger.getLevel();
        flywayLogger.setLevel(Level.DEBUG);
        try {
            migrate();
            String rotated = jdbc.queryForObject("SELECT uuid FROM users WHERE username = 'Alice'", String.class);
            String hashMarker = jdbc.queryForObject("SELECT pin_hash FROM users WHERE username = 'Alice'", String.class);
            try (var app = startApplication()) {
                assertNotNull(app.getBean(jakarta.persistence.EntityManagerFactory.class), "Hibernate validate must succeed on the migrated schema");
                TestRestTemplate rest = new TestRestTemplate();
                String base = "http://localhost:" + app.getWebServer().getPort();
                var users = app.getBean(UserRepository.class);
                var verification = app.getBean(TurnstileService.class);
                assertFalse(users.existsById(LEGACY_A));
                assertTrue(users.existsByAuthorId(LEGACY_A));
                assertEquals(404, post(rest, base + "/api/users/reconnect", Map.of("uuid", LEGACY_A), String.class).getStatusCode().value());
                // A valid bot token must not make the exposed public ID a placement bearer.
                assertEquals(404, post(rest, base + "/api/pixels", placement(LEGACY_A), String.class).getStatusCode().value());
                for (String command : List.of("CONNECT", "STOMP")) {
                    assertSocketRejected(app, command, LEGACY_A);
                }
                assertFalse(verification.isVerified(LEGACY_A));
                assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM pixels", Integer.class));

                var recovery = post(rest, base + "/api/users/recover", Map.of("username", "Alice", "pin", "9912"), UserIdentityResponse.class);
                assertEquals(200, recovery.getStatusCode().value());
                assertNotNull(recovery.getBody());
                assertTrue(rotated.equals(recovery.getBody().uuid()), "Recovery must return the rotated private UUID");
                var reconnect = post(rest, base + "/api/users/reconnect", Map.of("uuid", rotated), UserIdentityResponse.class);
                assertEquals(200, reconnect.getStatusCode().value());
                assertTrue(rotated.equals(reconnect.getBody().uuid()), "Reconnect must retain the recovered bearer");
                for (String command : List.of("CONNECT", "STOMP")) {
                    assertSocketAccepted(app, command, rotated);
                }
                assertEquals(201, post(rest, base + "/api/pixels", placement(rotated), String.class).getStatusCode().value());
                assertEquals(LEGACY_A, jdbc.queryForObject("SELECT author_id FROM pixels ORDER BY id DESC LIMIT 1", String.class));
            }
            String logs = output.getAll();
            assertTrue(logs.contains("DEBUG") && logs.contains("org.flywaydb"), "Verbose Flyway logging must actually be captured");
            assertTrue(logs.contains("POST /api/pixels"), "Application request logging must actually be captured");
            for (String marker : List.of(PEPPER, new String(PEPPER_BYTES, StandardCharsets.UTF_8), "9912", hashMarker, rotated)) {
                assertFalse(logs.contains(marker), "Migration/application logs exposed a credential marker");
            }
        } finally {
            flywayLogger.setLevel(previousLevel);
        }
    }

    @ParameterizedTest(name = "duplicate {0} race")
    @ValueSource(strings = {"uuid", "username"})
    void concurrentRequestsClassifyNativePostgresConflictsWithoutOverwritingTheWinner(String duplicate, CapturedOutput output) throws Exception {
        migrate();
        try (var app = startApplication()) {
            var credentials = app.getBean(PinCredentialService.class);
            var users = app.getBean(UserRepository.class);
            var verification = app.getBean(TurnstileService.class);
            CountDownLatch loserPrechecked = new CountDownLatch(1);
            CountDownLatch releaseLoser = new CountDownLatch(1);
            doAnswer(invocation -> {
                PinCredentialCodec.CanonicalPin pin = invocation.getArgument(0);
                if (pin.value().equals("Ab!9")) {
                    loserPrechecked.countDown();
                    assertTrue(releaseLoser.await(10, TimeUnit.SECONDS), "Winner did not release the stale request");
                }
                return invocation.callRealMethod();
            }).when(credentials).hash(any());
            String uuidA = UUID.randomUUID().toString();
            String uuidB = duplicate.equals("uuid") ? uuidA : UUID.randomUUID().toString();
            String nameB = duplicate.equals("username") ? "Winner" : "Loser";
            String endpoint = "http://localhost:" + app.getWebServer().getPort() + "/api/users";
            TestRestTemplate rest = new TestRestTemplate();
            try (var executor = Executors.newSingleThreadExecutor()) {
                var loser = executor.submit(() -> post(rest, endpoint, new UserCreateRequest(uuidB, nameB, "Ab!9", "Ab!9"), String.class));
                try {
                    assertTrue(loserPrechecked.await(10, TimeUnit.SECONDS), "The losing HTTP request must pass its prechecks before the winner commits");
                    var winner = post(rest, endpoint, new UserCreateRequest(uuidA, "Winner", "W!n7", "W!n7"), UserIdentityResponse.class);
                    assertEquals(201, winner.getStatusCode().value());
                    var committed = users.findById(uuidA).orElseThrow();
                    String authorId = committed.getAuthorId();
                    String hash = committed.getPinHash();
                    releaseLoser.countDown();
                    assertEquals(409, loser.get(10, TimeUnit.SECONDS).getStatusCode().value(), "Native PostgreSQL constraint names must translate into an identity conflict");
                    var preserved = users.findById(uuidA).orElseThrow();
                    assertEquals("Winner", preserved.getUsername());
                    assertEquals(authorId, preserved.getAuthorId());
                    assertTrue(hash.equals(preserved.getPinHash()), "The losing transaction must not replace the committed PIN hash");
                    assertEquals(1, users.count());
                    assertTrue(verification.isVerified(uuidA));
                    if (!uuidA.equals(uuidB)) assertFalse(verification.isVerified(uuidB));
                    for (String marker : List.of(uuidA, uuidB, "Ab!9", "W!n7", hash, PEPPER)) {
                        assertFalse(output.getAll().contains(marker), "Concurrent conflict logs exposed a credential marker");
                    }
                } finally {
                    releaseLoser.countDown();
                }
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBoundaries {
        @Bean
        static BeanPostProcessor externalVerificationAndRaceGate() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof TurnstileService service) {
                        var observed = spy(service);
                        doReturn(true).when(observed).verify("test-token");
                        return observed;
                    }
                    if (bean instanceof PinCredentialService service) return spy(service);
                    return bean;
                }
            };
        }
    }

    private ServletWebServerApplicationContext startApplication() {
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(BitbrushApplication.class, TestBoundaries.class).run(
            "--spring.profiles.active=test", "--server.port=0", "--spring.main.banner-mode=off",
            "--spring.datasource.url=" + databaseUrl(), "--spring.datasource.username=" + POSTGRES.getUsername(),
            "--spring.datasource.password=" + POSTGRES.getPassword(), "--spring.datasource.driver-class-name=org.postgresql.Driver",
            "--spring.jpa.hibernate.ddl-auto=validate", "--spring.flyway.enabled=true",
            "--spring.flyway.default-schema=" + schema, "--spring.flyway.schemas=" + schema,
            "--spring.flyway.placeholders.pin-pepper=" + PEPPER, "--pin.pepper=" + PEPPER,
            "--pin.max-concurrent=2", "--bitbrush.placement.earn-rate-seconds=3600",
            "--logging.level.org.flywaydb=DEBUG", "--logging.level.au.com.dingwall.mark.bitbrush=TRACE");
    }

    private <T> ResponseEntity<T> post(TestRestTemplate rest, String url, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Turnstile-Token", "test-token");
        return rest.postForEntity(url, new HttpEntity<>(body, headers), type);
    }

    private Map<String, Object> placement(String uuid) {
        return Map.of("pixels", List.of(Map.of("x", 15, "y", 16)), "paletteIndex", 42, "authorUuid", uuid);
    }

    private void assertSocketRejected(ServletWebServerApplicationContext app, String command, String uuid) throws Exception {
        try (RawSocket socket = connectSocket(app)) {
            socket.send(connectionFrame(command, uuid) + "SUBSCRIBE\nid:bank\ndestination:/app/bank\n\n\0");
            socket.closed.get(5, TimeUnit.SECONDS);
            assertTrue(socket.frames.stream().noneMatch(frame -> frame.startsWith("CONNECTED") || frame.startsWith("MESSAGE")),
                "A legacy public ID must receive no authenticated connection or bank response");
            assertEquals(0, app.getBean(SimpUserRegistry.class).getUserCount());
        }
    }

    private void assertSocketAccepted(ServletWebServerApplicationContext app, String command, String uuid) throws Exception {
        SimpUserRegistry registry = app.getBean(SimpUserRegistry.class);
        try (RawSocket socket = connectSocket(app)) {
            socket.send(connectionFrame(command, uuid) + "SUBSCRIBE\nid:bank\ndestination:/app/bank\n\n\0");
            await().atMost(Duration.ofSeconds(5)).until(() -> socket.frames.stream().anyMatch(frame -> frame.startsWith("MESSAGE")));
            assertTrue(socket.frames.stream().anyMatch(frame -> frame.startsWith("CONNECTED")));
            assertTrue(socket.frames.stream().anyMatch(frame -> frame.contains("\"balance\":5")), "The recovered bearer must reach its own bank");
            assertNotNull(registry.getUser(uuid));
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> registry.getUserCount() == 0);
    }

    private RawSocket connectSocket(ServletWebServerApplicationContext app) throws Exception {
        RawSocket socket = new RawSocket();
        socket.session = new StandardWebSocketClient().execute(socket, new WebSocketHttpHeaders(),
            URI.create("ws://localhost:" + app.getWebServer().getPort() + "/ws/websocket")).get(5, TimeUnit.SECONDS);
        return socket;
    }

    private String connectionFrame(String command, String uuid) {
        return command + "\naccept-version:1.2\nhost:localhost\nheart-beat:0,0\nuuid:" + uuid + "\n\n\0";
    }

    private static final class RawSocket extends TextWebSocketHandler implements AutoCloseable {
        final List<String> frames = new CopyOnWriteArrayList<>();
        final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        WebSocketSession session;
        void send(String frame) throws Exception { session.sendMessage(new TextMessage(frame)); }
        @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            frames.addAll(Arrays.asList(message.getPayload().split("\0")));
        }
        @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { closed.complete(status); }
        @Override public void close() throws Exception { if (session.isOpen()) session.close(); }
    }

    private PinCredentialCodec codec(byte[] pepper) {
        return new PinCredentialCodec(pepper, 32, 1, 1, 16);
    }

    private void assertConstraint(String expected, String sql, Object... values) {
        var error = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql, values));
        assertTrue(error.getMostSpecificCause() instanceof java.sql.SQLException cause
            && "23505".equals(cause.getSQLState()) && cause.getMessage().contains('"' + expected + '"'),
            "PostgreSQL must report the identity constraint consumed by the service");
    }

    private void migrate() {
        configuration().placeholders(Map.of("pin-pepper", PEPPER)).load().migrate();
    }

    private void seedLegacyUsersAndPixels() {
        jdbc.update("INSERT INTO users(uuid, username) VALUES (?, ?), (?, ?)", LEGACY_A, "Alice", LEGACY_B, "Bob");
        jdbc.update("""
            INSERT INTO pixels(x, y, palette_index, author_uuid, placed_at)
            VALUES (1, 2, 42, ?, '2020-01-01T00:00:00Z'),
                   (1, 2, 0, ?, '2020-01-02T00:00:00Z'),
                   (3, 4, 99, ?, '2020-01-03T00:00:00Z')
            """, LEGACY_A, LEGACY_B, LEGACY_A);
    }

    private List<Map<String, Object>> pixelRows(String authorColumn) {
        return jdbc.queryForList("SELECT id, x, y, palette_index, " + authorColumn
            + " AS author, placed_at, xmin::text AS version, ctid::text AS tuple FROM pixels ORDER BY id");
    }

    private FluentConfiguration configuration() {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema).defaultSchema(schema).locations("classpath:db/migration");
    }

    private String databaseUrl() {
        return POSTGRES.getJdbcUrl() + "&currentSchema=" + schema;
    }
}
