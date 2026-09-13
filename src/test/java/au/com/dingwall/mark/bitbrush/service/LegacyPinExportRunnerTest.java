package au.com.dingwall.mark.bitbrush.service;

import au.com.dingwall.mark.bitbrush.config.PinProperties;
import au.com.dingwall.mark.bitbrush.model.User;
import au.com.dingwall.mark.bitbrush.repository.UserRepository;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
class LegacyPinExportRunnerTest {

    private static final String PEPPER = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    // Independently checked with Node's HMAC-SHA256 for the zero-byte test pepper.
    private static final byte[] EXPECTED = "Alice\t5504\nZoë\t3390\n".getBytes(StandardCharsets.UTF_8);
    private static final DefaultApplicationArguments ARGUMENTS = new DefaultApplicationArguments(new String[0]);

    @Autowired UserRepository users;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    @TempDir
    Path root;

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "\t\r\n"})
    void blankExportPathDoesNoFilesystemWork(String path) throws Exception {
        legacyRows();

        runner(path).run(ARGUMENTS);

        assertNoFilesystemWork();
    }

    @Test
    void noBackfilledRowsDoesNoFilesystemWork() throws Exception {
        save("current-private-id", "Current", "author-current", false);

        runner(target().toString()).run(ARGUMENTS);

        assertNoFilesystemWork();
    }

    @Test
    void exportsOnlyBackfilledRowsExactlyOnceInUsernameOrderAsUtf8() throws Exception {
        legacyRows();
        save("current-private-id", "Current", "author-current", false);
        List<Map<String, Object>> before = databaseSnapshot();

        runner(target().toString()).run(ARGUMENTS);

        assertTrue(Arrays.equals(EXPECTED, Files.readAllBytes(target())), "Export content, order, or row selection differs");
        assertTrue(before.equals(databaseSnapshot()), "Export mutated stored identities");
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad\tname", "bad\rname", "bad\nname"})
    void rejectsTsvDelimiterUsernamesBeforeAnyFilesystemWork(String username) {
        save("bad-private-id", username, "author-alice", true);
        List<Map<String, Object>> before = databaseSnapshot();

        assertThrows(IllegalStateException.class, () -> runner(target().toString()).run(ARGUMENTS));

        assertNoFilesystemWork();
        assertTrue(before.equals(databaseSnapshot()), "Rejected export mutated stored identities");
    }

    @Test
    void rejectsRelativePathEvenWhenNoBackfilledRowsExist() {
        assertThrows(IllegalStateException.class, () -> runner("relative/export.tsv").run(ARGUMENTS));
        assertNoFilesystemWork();
    }

    @Test
    void deletionFollowedByRerunReproducesTheSameBytes() throws Exception {
        legacyRows();
        runner(target().toString()).run(ARGUMENTS);
        byte[] first = Files.readAllBytes(target());
        Files.delete(target());

        runner(target().toString()).run(ARGUMENTS);

        assertTrue(Arrays.equals(EXPECTED, Files.readAllBytes(target())), "Recreated export differs from expected bytes");
        assertTrue(Arrays.equals(first, Files.readAllBytes(target())), "Restart changed the export");
    }

    @Test
    void acceptsValidExistingExportOnStartupWithoutReplacingIt() throws Exception {
        legacyRows();
        runner(target().toString()).run(ARGUMENTS);
        Object originalInode = Files.getAttribute(target(), "unix:ino");

        runner(target().toString()).run(ARGUMENTS);

        assertEquals(originalInode, Files.getAttribute(target(), "unix:ino"));
        assertTrue(Arrays.equals(EXPECTED, Files.readAllBytes(target())), "Restart changed existing export content");
    }

    @Test
    void unsafePublicationFailsTheStartupRunnerWithoutDatabaseMutation() throws Exception {
        legacyRows();
        List<Map<String, Object>> before = databaseSnapshot();
        Files.createDirectory(target().getParent(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));

        assertThrows(IllegalStateException.class, () -> runner(target().toString()).run(ARGUMENTS));

        assertFalse(Files.exists(target()));
        assertTrue(before.equals(databaseSnapshot()), "Failed publication mutated stored identities");
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test", "docker", "prod"})
    void configuredEnvironmentPathEnablesExportInEverySupportedProfile(String profile) throws Exception {
        legacyRows();
        MockEnvironment environment = new MockEnvironment()
            .withProperty("PIN_PEPPER", PEPPER)
            .withProperty("PIN_BACKFILL_EXPORT_PATH", target().toString());
        environment.getPropertySources().addLast(new ResourcePropertySource("classpath:application-" + profile + ".properties"));
        PinProperties bound = Binder.get(environment).bind("pin", Bindable.of(PinProperties.class)).get();

        new LegacyPinExportRunner(bound, users, credentials(), new SecureExportPublisher(), "local").run(ARGUMENTS);

        assertTrue(Files.isRegularFile(target()), "Profile did not enable the configured export path");
        assertTrue(Arrays.equals(EXPECTED, Files.readAllBytes(target())), "Profile export content differs");
    }

    @Test
    void successAndFailureLogsExposeOnlyOperationalMetadataAndNoCredentials() throws Exception {
        legacyRows();
        String privateId = "alice-private-id";
        String encodedHash = users.findById(privateId).orElseThrow().getPinHash();
        String pin = "5504";
        String secretFailure = String.join(" ", pin, encodedHash, PEPPER, privateId);
        Logger logger = (Logger) LoggerFactory.getLogger("au.com.dingwall.mark.bitbrush.service");
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        logger.setAdditive(false);
        logger.setLevel(Level.TRACE);
        logger.addAppender(capture);
        try {
            new LegacyPinExportRunner(properties(target().toString()), users, credentials(),
                new SecureExportPublisher(), "machine-example").run(ARGUMENTS);
            SecureExportPublisher failingPublisher = new SecureExportPublisher() {
                @Override
                public void publish(Path path, byte[] content) throws IOException {
                    throw new IOException(secretFailure);
                }
            };
            IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                new LegacyPinExportRunner(properties(target().toString()), users, credentials(),
                    failingPublisher, "machine-example").run(ARGUMENTS));

            assertFalse(capture.list.isEmpty(), "Logging capture observed no export operation");
            StringBuilder logged = new StringBuilder();
            for (ILoggingEvent event : capture.list) {
                assertTrue(event.getFormattedMessage().contains(target().toString()), "Export log omitted the path");
                assertTrue(event.getFormattedMessage().contains("machine-example"), "Export log omitted the machine identifier");
                assertTrue(Arrays.stream(event.getArgumentArray()).anyMatch(argument -> Integer.valueOf(2).equals(argument)),
                    "Export log omitted the row count");
                logged.append(event.getFormattedMessage().replace(target().toString(), "[export-path]"));
                if (event.getThrowableProxy() != null) logged.append(ThrowableProxyUtil.asString(event.getThrowableProxy()));
            }
            for (String forbidden : List.of(pin, "3390", encodedHash, PEPPER, privateId, "Alice", "Zoë", "author-alice")) {
                assertTrue(logged.indexOf(forbidden) < 0, "Export leaked sensitive data into logs");
                assertFalse(failure.toString().contains(forbidden), "Startup failure rendered sensitive data");
            }
            assertNull(failure.getCause(), "Startup exception retained a potentially sensitive dependency exception");
        } finally {
            logger.detachAppender(capture);
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
            capture.stop();
        }
    }

    private LegacyPinExportRunner runner(String path) {
        return new LegacyPinExportRunner(properties(path), users, credentials(), new SecureExportPublisher(), "local");
    }

    private PinProperties properties(String path) {
        return new PinProperties(PEPPER, 32, 1, 1, 16, 1, 1, Duration.ofMinutes(15), 5, 20, 100, 100, path);
    }

    private PinCredentialService credentials() {
        return new PinCredentialService(properties(""));
    }

    private Path target() {
        return root.resolve("private/export.tsv");
    }

    private void legacyRows() {
        save("zoe-private-id", "Zoë", "author-zoe", true);
        save("alice-private-id", "Alice", "author-alice", true);
    }

    private void save(String uuid, String username, String authorId, boolean backfilled) {
        User user = new User();
        user.setUuid(uuid);
        user.setUsername(username);
        user.setAuthorId(authorId);
        user.setPinHash(credentials().hash(credentials().canonicalize("Ab!9")));
        user.setPinBackfilled(backfilled);
        users.saveAndFlush(user);
    }

    private List<Map<String, Object>> databaseSnapshot() {
        entityManager.flush();
        entityManager.clear();
        return jdbc.queryForList("SELECT uuid, username, author_id, pin_hash, pin_backfilled FROM users ORDER BY username");
    }

    private void assertNoFilesystemWork() {
        assertDoesNotThrow(() -> {
            try (var children = Files.list(root)) {
                assertEquals(0, children.count(), "Disabled or empty export created a filesystem artifact");
            }
        });
    }
}
