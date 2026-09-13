package au.com.dingwall.mark.bitbrush;

import au.com.dingwall.mark.bitbrush.config.*;
import au.com.dingwall.mark.bitbrush.controller.PixelController;
import au.com.dingwall.mark.bitbrush.controller.UserController;
import au.com.dingwall.mark.bitbrush.dto.*;
import au.com.dingwall.mark.bitbrush.exception.*;
import au.com.dingwall.mark.bitbrush.model.User;
import au.com.dingwall.mark.bitbrush.repository.*;
import au.com.dingwall.mark.bitbrush.service.*;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.CloseStatus;
import au.com.dingwall.mark.bitbrush.websocket.WebSocketEventListener;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class SensitiveDataLoggingTest {
    @ParameterizedTest
    @ValueSource(strings = {"DEBUG", "TRACE"})
    void identityPixelAndErrorPathsKeepCredentialsOutOfApplicationLogsAndProblems(String level) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("au.com.dingwall.mark.bitbrush");
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        logger.setAdditive(false);
        logger.setLevel(Level.toLevel(level));
        logger.addAppender(capture);
        try {
            String privateId = UUID.randomUUID().toString();
            String missingId = UUID.randomUUID().toString();
            String pin = "Q😺v?";
            byte[] pepperBytes = new byte[32];
            new SecureRandom().nextBytes(pepperBytes);
            String pepper = Base64.getEncoder().encodeToString(pepperBytes);
            PinProperties properties = new PinProperties(pepper, 32, 1, 1, 16, 1, 1, Duration.ofMinutes(15), 5, 20, 100, 100);
            PinCredentialService credentials = new PinCredentialService(properties);
            UserRepository users = mock(UserRepository.class);
            PixelRepository pixels = mock(PixelRepository.class);
            SimpUserRegistry registry = mock(SimpUserRegistry.class);
            SimpUser connectedUser = mock(SimpUser.class);
            when(connectedUser.getName()).thenReturn(privateId);
            when(registry.getUsers()).thenReturn(java.util.Set.of(connectedUser));
            BankingService bank = new BankingService(mock(SimpMessagingTemplate.class),
                new BitbrushProperties(new BitbrushProperties.Canvas(250, 250),
                    new BitbrushProperties.Placement(3, 25, 5)), registry);
            bank.ensureBank(privateId);
            AtomicReference<User> stored = new AtomicReference<>();
            when(users.saveAndFlush(any())).thenAnswer(invocation -> {
                User user = invocation.getArgument(0);
                stored.set(user);
                return user;
            });
            when(users.findById(any())).thenAnswer(invocation -> Optional.ofNullable(stored.get())
                .filter(user -> user.getUuid().equals(invocation.getArgument(0))));
            when(users.findByUsername(any())).thenAnswer(invocation -> Optional.ofNullable(stored.get())
                .filter(user -> user.getUsername().equals(invocation.getArgument(0))));
            TurnstileService turnstile = spy(new TurnstileService(new TurnstileProperties("site-key", "test-secret"), RestClient.builder()));
            doReturn(true).when(turnstile).verify(any());
            PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
            UserIdentityService identities = new UserIdentityService(users, credentials,
                new RecoveryAttemptService(properties, Clock.systemUTC()), turnstile,
                new AuthorIdGenerator(new SecureRandom()), new TransactionTemplate(manager));
            PixelService pixelService = new PixelService(pixels, users, List.of("#000000", "#ff0000"),
                mock(BitbrushProperties.class), mock(SimpMessagingTemplate.class), bank);
            GlobalExceptionHandler handler = new GlobalExceptionHandler();
            MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new UserController(identities, new ClientIpResolver(new MockEnvironment())),
                new PixelController(pixelService, turnstile)).setControllerAdvice(handler).build();
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            assertEquals(201, postJson(mvc, mapper, "/api/users", new UserCreateRequest(privateId, "PrivacyArtist", pin, pin)).getStatus());
            String hash = stored.get().getPinHash();
            assertEquals(200, postJson(mvc, mapper, "/api/users/reconnect", new UserReconnectRequest(privateId)).getStatus());
            assertEquals(200, postJson(mvc, mapper, "/api/users/recover", new UserRecoveryRequest("PrivacyArtist", pin)).getStatus());
            var failed = postJson(mvc, mapper, "/api/users/reconnect", new UserReconnectRequest(missingId));
            assertEquals(404, failed.getStatus());
            assertEquals(201, postJson(mvc, mapper, "/api/pixels",
                new PixelPlacementRequest(List.of(new PixelCoordinate(1, 2)), 1, privateId)).getStatus());

            when(users.existsById(privateId)).thenReturn(true);
            var connect = StompHeaderAccessor.create(StompCommand.CONNECT);
            connect.setSessionId("privacy-session");
            connect.setNativeHeader("uuid", privateId);
            connect.setLeaveMutable(true);
            var connectMessage = MessageBuilder.createMessage(new byte[0], connect.getMessageHeaders());
            new StompAuthenticationInterceptor(users, turnstile).preSend(connectMessage, null);
            var connected = StompHeaderAccessor.create(StompCommand.CONNECTED);
            connected.setSessionId("privacy-session");
            connected.setHeader("simpConnectMessage", connectMessage);
            var connectedMessage = MessageBuilder.createMessage(new byte[0], connected.getMessageHeaders());
            var listener = new WebSocketEventListener(mock(SimpMessagingTemplate.class), bank);
            listener.handleConnect(new SessionConnectedEvent(this, connectedMessage, connect.getUser()));
            bank.earnPoints();
            bank.deductPoint(privateId);
            listener.handleDisconnect(new SessionDisconnectEvent(this, connectedMessage, "privacy-session",
                CloseStatus.NORMAL, connect.getUser()));

            String markers = String.join(" ", privateId, missingId, pin, hash, pepper);
            String problems = failed.getContentAsString()
                + mapper.writeValueAsString(handler.handleIllegalArgument(new IllegalArgumentException(markers)))
                + mapper.writeValueAsString(handler.handleDataIntegrityViolation(new DataIntegrityViolationException(markers)))
                + mapper.writeValueAsString(handler.handleTurnstileFailure(new TurnstileException(markers)));

            RestClient.Builder builder = mock(RestClient.Builder.class, RETURNS_SELF);
            RestClient client = mock(RestClient.class);
            when(builder.build()).thenReturn(client);
            when(client.post()).thenThrow(new IllegalStateException(markers));
            TurnstileService failingTurnstile = new TurnstileService(new TurnstileProperties("site-key", "test-secret"), builder);
            assertFalse(failingTurnstile.verify("test-token"));

            assertFalse(capture.list.isEmpty(), "The logging capture did not observe exercised application paths");
            StringBuilder output = new StringBuilder();
            for (ILoggingEvent event : capture.list) {
                output.append(event.getFormattedMessage());
                if (event.getThrowableProxy() != null) output.append(ThrowableProxyUtil.asString(event.getThrowableProxy()));
            }
            for (String forbidden : List.of(privateId, missingId, pin, hash, pepper)) {
                assertTrue(output.indexOf(forbidden) < 0, "Sensitive data reached application logs");
                assertTrue(problems.indexOf(forbidden) < 0, "Sensitive data reached a problem response");
            }
        } finally {
            logger.detachAppender(capture);
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
            capture.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test", "docker", "prod"})
    void supportedProfilesKeepCredentialRenderingFrameworkLoggersAtInfoOrHigher(String profile) throws Exception {
        java.util.Properties properties = new java.util.Properties();
        try (var resource = getClass().getResourceAsStream("/application-" + profile + ".properties")) {
            properties.load(resource);
        }
        for (String category : List.of("org.springframework.messaging.simp",
                "org.springframework.web.socket.messaging", "org.springframework.web.SimpLogging")) {
            String configured = properties.getProperty("logging.level." + category);
            assertNotNull(configured, "Each supported profile must protect " + category);
            assertTrue(Level.toLevel(configured, Level.TRACE).isGreaterOrEqual(Level.INFO),
                "Framework credential rendering must remain disabled for " + category);
        }
    }

    private org.springframework.mock.web.MockHttpServletResponse postJson(MockMvc mvc, ObjectMapper mapper,
            String path, Object body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).header("X-Turnstile-Token", "token")
                .with(request -> { request.setRemoteAddr(InetAddress.getLoopbackAddress().getHostAddress()); return request; })
                .content(mapper.writeValueAsBytes(body)))
            .andReturn().getResponse();
    }
}
