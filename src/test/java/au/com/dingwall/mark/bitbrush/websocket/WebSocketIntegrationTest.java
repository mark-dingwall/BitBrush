package au.com.dingwall.mark.bitbrush.websocket;

import au.com.dingwall.mark.bitbrush.VerificationCacheTestSupport;
import au.com.dingwall.mark.bitbrush.dto.BankStateResponse;
import au.com.dingwall.mark.bitbrush.dto.PixelBroadcast;
import au.com.dingwall.mark.bitbrush.model.User;
import au.com.dingwall.mark.bitbrush.repository.PixelRepository;
import au.com.dingwall.mark.bitbrush.repository.UserRepository;
import au.com.dingwall.mark.bitbrush.service.BankingService;
import au.com.dingwall.mark.bitbrush.service.TurnstileService;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.*;
import org.springframework.messaging.*;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.messaging.*;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.net.URI;
import java.security.Principal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "bitbrush.placement.earn-rate-seconds=3600",
    "logging.level.au.com.dingwall.mark.bitbrush=TRACE"
})
@ActiveProfiles("test")
@Import({WebSocketIntegrationTest.Events.class, WebSocketIntegrationTest.ProbeController.class})
class WebSocketIntegrationTest {
    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;
    @Autowired BankingService bank;
    @MockitoSpyBean UserRepository users;
    @Autowired PixelRepository pixels;
    @Autowired SimpUserRegistry registry;
    @Autowired WebSocketEventListener presence;
    @Autowired Events events;
    @Autowired ProbeController probe;
    @Autowired @Qualifier("clientInboundChannel") ExecutorSubscribableChannel inbound;
    @Autowired @Qualifier("clientInboundChannelExecutor") ThreadPoolTaskExecutor inboundExecutor;
    @Autowired @Qualifier("clientOutboundChannelExecutor") ThreadPoolTaskExecutor outboundExecutor;
    @MockitoSpyBean TurnstileService turnstile;

    private final List<StompSession> sessions = new ArrayList<>();
    private final List<WebSocketStompClient> clients = new ArrayList<>();
    private final List<RawSocket> sockets = new ArrayList<>();
    private final List<ILoggingEvent> logs = new CopyOnWriteArrayList<>();
    private AppenderBase<ILoggingEvent> capture;

    static class Events {
        final List<SessionConnectEvent> connecting = new CopyOnWriteArrayList<>();
        final List<SessionConnectedEvent> connected = new CopyOnWriteArrayList<>();
        final AtomicInteger subscriptions = new AtomicInteger();
        @EventListener void connecting(SessionConnectEvent event) { connecting.add(event); }
        @EventListener void connected(SessionConnectedEvent event) { connected.add(event); }
        @EventListener void subscribed(SessionSubscribeEvent event) { subscriptions.incrementAndGet(); }
        void clear() { connecting.clear(); connected.clear(); subscriptions.set(0); }
    }

    @Controller
    @TestComponent
    static class ProbeController {
        final BlockingQueue<String> invocations = new LinkedBlockingQueue<>();
        @MessageMapping("/probe")
        void invoke(Principal principal) { invocations.add(principal.getName()); }
    }

    @BeforeEach
    void setUp() {
        await().atMost(Duration.ofSeconds(5)).until(() -> registry.getUserCount() == 0 && presence.getCount() == 0);
        events.clear();
        probe.invocations.clear();
        doReturn(true).when(turnstile).verify(any());
        capture = new AppenderBase<>() {
            @Override protected void append(ILoggingEvent event) { logs.add(event); }
        };
        capture.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(capture);
        for (String category : List.of("org.springframework.messaging.simp",
                "org.springframework.web.socket.messaging", "org.springframework.web.SimpLogging")) {
            assertTrue(((Logger) LoggerFactory.getLogger(category)).getEffectiveLevel().isGreaterOrEqual(Level.INFO));
        }
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (StompSession session : sessions) if (session.isConnected()) session.disconnect();
        for (RawSocket socket : sockets) if (socket.session.isOpen()) socket.session.close();
        await().atMost(Duration.ofSeconds(5)).until(() -> registry.getUserCount() == 0 && presence.getCount() == 0);
        drainHandlers();
        clients.forEach(WebSocketStompClient::stop);
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(capture);
        capture.stop();
        pixels.deleteAll();
        users.deleteAll();
    }

    @Test
    void pixelBroadcastContainsOnlyPublicAuthorship() throws Exception {
        User user = identity();
        StompSession session = connect(user.getUuid());
        BlockingQueue<PixelBroadcast> received = new LinkedBlockingQueue<>();
        session.subscribe("/topic/pixels", handler(PixelBroadcast.class, received));
        bankBarrier(session);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response = rest.postForEntity("/api/pixels", new HttpEntity<>(Map.of(
            "pixels", List.of(Map.of("x", 5, "y", 10)), "paletteIndex", 42, "authorUuid", user.getUuid()), headers), Void.class);
        assertEquals(201, response.getStatusCode().value());
        PixelBroadcast broadcast = received.poll(5, TimeUnit.SECONDS);
        assertNotNull(broadcast);
        assertEquals(5, broadcast.x());
        assertEquals(10, broadcast.y());
        assertEquals(user.getAuthorId(), broadcast.authorId());
        assertFalse(mapper.writeValueAsString(broadcast).contains(user.getUuid()));
        assertNoCredentialLogs(user.getUuid());
    }

    @Test
    void onlineCountCountsSessionsAndDuplicateDisconnectsDoNotChangeIt() throws Exception {
        User user = identity();
        StompSession first = connect(user.getUuid());
        BlockingQueue<Integer> counts = new LinkedBlockingQueue<>();
        first.subscribe("/topic/users/count", handler(Integer.class, counts));
        bankBarrier(first);
        StompSession second = connect(user.getUuid());
        assertEquals(2, counts.poll(5, TimeUnit.SECONDS));
        second.disconnect();
        awaitSessions(user.getUuid(), 1);
        assertEquals(1, counts.poll(5, TimeUnit.SECONDS));
        assertEquals(1, presence.getCount());
    }

    @ParameterizedTest
    @CsvSource({"CONNECT,missing", "STOMP,missing", "CONNECT,blank", "STOMP,blank",
        "CONNECT,malformed", "STOMP,malformed", "CONNECT,public", "STOMP,public",
        "CONNECT,legacyPublic", "STOMP,legacyPublic", "CONNECT,unknown", "STOMP,unknown",
        "CONNECT,repositoryFailure", "STOMP,repositoryFailure"})
    void invalidConnectionPipelineCannotReachAnyAuthenticatedSurface(String command, String kind) throws Exception {
        User existing = identity();
        String value = switch (kind) {
            case "missing" -> null;
            case "blank" -> "   ";
            case "malformed" -> "submitted-invalid-credential";
            case "public" -> existing.getAuthorId();
            case "legacyPublic" -> {
                String authorId = UUID.randomUUID().toString();
                existing.setAuthorId(authorId);
                users.saveAndFlush(existing);
                yield authorId;
            }
            case "repositoryFailure" -> {
                doThrow(new IllegalStateException("Private repository failure " + existing.getUuid()))
                    .when(users).existsById(existing.getUuid());
                yield existing.getUuid();
            }
            default -> UUID.randomUUID().toString();
        };
        assertRejectedPipeline(command, value);
    }

    @Test
    void invalidStompPipelineKeepsCredentialsOutOfErrorLevelFrameworkLogs() throws Exception {
        List<Logger> framework = List.of(
            (Logger) LoggerFactory.getLogger("org.springframework.messaging.simp"),
            (Logger) LoggerFactory.getLogger("org.springframework.web.socket.messaging"),
            (Logger) LoggerFactory.getLogger("org.springframework.web.SimpLogging"));
        List<Level> previous = framework.stream().map(Logger::getLevel).toList();
        try {
            framework.forEach(logger -> logger.setLevel(Level.ERROR));
            assertRejectedPipeline("STOMP", UUID.randomUUID().toString());
        } finally {
            for (int index = 0; index < framework.size(); index++) framework.get(index).setLevel(previous.get(index));
        }
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"CONNECT", "STOMP"})
    void malformedAuthenticatedSubscriptionKeepsPrivateIdentityOutOfErrorLogs(StompCommand command) throws Exception {
        User user = identity();
        RawSocket socket = raw();
        socket.send(connection(command.name(), user.getUuid()));
        awaitSessions(user.getUuid(), 1);
        await().atMost(Duration.ofSeconds(5)).until(() -> socket.frames.stream()
            .anyMatch(frame -> frame.startsWith("CONNECTED")));

        socket.send("SUBSCRIBE\ndestination:/topic/pixels\n\n\0"
            + "SEND\ndestination:/app/probe\n\n\0"
            + "SUBSCRIBE\nid:privacy-barrier\ndestination:/app/bank\n\n\0");
        await().atMost(Duration.ofSeconds(5)).until(() -> socket.frames.stream().anyMatch(frame ->
            frame.startsWith("MESSAGE") && frame.contains("subscription:privacy-barrier")));
        drainHandlers();

        assertTrue(logs.stream().anyMatch(event -> event.getLevel() == Level.ERROR
            && event.getFormattedMessage().startsWith("No subscriptionId")),
            "The regression must exercise the framework's ERROR-level message rendering");
        assertEquals(user.getUuid(), probe.invocations.poll(5, TimeUnit.SECONDS),
            "The authenticated Principal name must still route application commands");
        assertEquals(5, bank.getInitialState(user.getUuid()).balance());
        assertNoCredentialLogs(user.getUuid());
    }

    private void assertRejectedPipeline(String command, String value) throws Exception {
        clearInvocations(turnstile);
        RawSocket socket = raw();
        socket.send(connection(command, value) + followOnFrames());
        socket.closed.get(5, TimeUnit.SECONDS);
        drainHandlers();
        assertTrue(socket.frames.stream().anyMatch(frame -> frame.startsWith("ERROR")) || !socket.session.isOpen());
        assertTrue(socket.frames.stream().noneMatch(frame -> frame.startsWith("CONNECTED")));
        assertTrue(socket.frames.stream().noneMatch(frame -> frame.startsWith("MESSAGE")),
            "Invalid pipeline received an application response or pixel/bank broadcast");
        assertTrue(events.connecting.isEmpty(), "Invalid identity published a SessionConnectEvent");
        assertTrue(events.connected.isEmpty());
        assertEquals(0, registry.getUserCount());
        assertEquals(0, presence.getCount());
        assertEquals(0, events.subscriptions.get());
        assertTrue(probe.invocations.isEmpty());
        assertEquals(0, pixels.count());
        if (value != null) assertEquals(0, bank.deductPoints(value, 1), "Rejected connection initialized a bank");
        verify(turnstile, never()).markVerified(any());
        assertNoCredentialLogs(value);
        // Spring deliberately skips ERROR logging for rejected CONNECT frames.
        // Capture all emitted levels, including ERROR; rejection is observed on the wire.
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"CONNECT", "STOMP"})
    void validSingleMessagePipelineAuthenticatesBeforeSendAndSubscribe(StompCommand command) throws Exception {
        User user = identity();
        assertFalse(turnstile.isVerified(user.getUuid()));
        RawSocket socket = raw();
        socket.send(connection(command.name(), user.getUuid()) + followOnFrames());
        await().atMost(Duration.ofSeconds(5)).until(() -> socket.frames.stream().anyMatch(frame ->
            frame.startsWith("MESSAGE") && frame.contains("subscription:initial")));
        assertEquals(user.getUuid(), probe.invocations.poll(5, TimeUnit.SECONDS));
        awaitSessions(user.getUuid(), 1);
        assertTrue(turnstile.isVerified(user.getUuid()));
        assertEquals(user.getUuid(), events.connecting.getFirst().getUser().getName());
        assertEquals(user.getUuid(), events.connected.getFirst().getUser().getName());
        assertTrue(socket.frames.stream().anyMatch(frame -> frame.startsWith("CONNECTED")));
        assertEquals(5, bank.getInitialState(user.getUuid()).balance());
        assertNoCredentialLogs(user.getUuid());
    }

    @Test
    void authenticationCompletesEvenWhenDownstreamConnectHandlingIsBlocked() throws Exception {
        assertEquals(1, inboundExecutor.getCorePoolSize(), "Handler FIFO requires one inbound worker");
        assertEquals(1, inboundExecutor.getMaxPoolSize(), "Handler FIFO must hold under load");
        User user = identity();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorChannelInterceptor blocker = new ExecutorChannelInterceptor() {
            @Override public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
                StompHeaderAccessor headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (headers != null && headers.getCommand() == StompCommand.CONNECT) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Connect blocker was not released");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(exception);
                    }
                }
                return message;
            }
        };
        inbound.addInterceptor(blocker);
        try {
            RawSocket socket = raw();
            socket.send(connection("CONNECT", user.getUuid()) + followOnFrames());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(turnstile.isVerified(user.getUuid()), "Verification must happen in the synchronous interceptor");
            assertEquals(0, registry.getUserCount());
            assertTrue(probe.invocations.isEmpty(), "Pipelined SEND overtook blocked CONNECT");
            assertTrue(events.connected.isEmpty());
            release.countDown();
            assertEquals(user.getUuid(), probe.invocations.poll(5, TimeUnit.SECONDS));
            awaitSessions(user.getUuid(), 1);
            assertNoCredentialLogs(user.getUuid());
        } finally {
            release.countDown();
            inbound.removeInterceptor(blocker);
        }
    }

    @Test
    void reconnectRestoresAClearedCacheAndDisconnectRetainsVerificationAndBalance() throws Exception {
        User user = identity();
        StompSession first = connect(user.getUuid());
        bankBarrier(first);
        bank.deductPoint(user.getUuid());
        first.disconnect();
        awaitSessions(user.getUuid(), 0);
        assertTrue(turnstile.isVerified(user.getUuid()));
        assertEquals(4, bank.getInitialState(user.getUuid()).balance());
        VerificationCacheTestSupport.clearVerification(turnstile);
        assertFalse(turnstile.isVerified(user.getUuid()));
        StompSession second = connect(user.getUuid());
        assertEquals(4, bankBarrier(second).balance());
        assertTrue(turnstile.isVerified(user.getUuid()));
        second.disconnect();
        awaitSessions(user.getUuid(), 0);
        assertTrue(turnstile.isVerified(user.getUuid()));
        bank.earnPoints();
        assertEquals(4, bank.getInitialState(user.getUuid()).balance());
        assertNoCredentialLogs(user.getUuid());
    }

    @Test
    void twoSessionsEarnOncePerPrincipalAndBothReceiveExactlyOneUpdate() throws Exception {
        User user = identity();
        StompSession first = connect(user.getUuid());
        StompSession second = connect(user.getUuid());
        BlockingQueue<BankStateResponse> firstUpdates = new LinkedBlockingQueue<>();
        BlockingQueue<BankStateResponse> secondUpdates = new LinkedBlockingQueue<>();
        first.subscribe("/user/queue/bank", handler(BankStateResponse.class, firstUpdates));
        BankStateResponse firstInitial = bankBarrier(first);
        second.subscribe("/user/queue/bank", handler(BankStateResponse.class, secondUpdates));
        BankStateResponse secondInitial = bankBarrier(second);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertEquals(1, registry.getUserCount());
            var registered = registry.getUser(user.getUuid());
            assertNotNull(registered);
            assertEquals(2, registered.getSessions().size());
            assertTrue(registered.getSessions().stream().allMatch(session ->
                session.getSubscriptions().stream().anyMatch(sub -> sub.getDestination().equals("/user/queue/bank"))
                && session.getSubscriptions().stream().anyMatch(sub -> sub.getDestination().equals("/app/bank"))));
        });
        assertEquals(5, firstInitial.balance());
        assertEquals(5, secondInitial.balance());
        assertTrue(firstUpdates.isEmpty());
        assertTrue(secondUpdates.isEmpty());

        bank.earnPoints();
        assertBalance(firstUpdates, 6);
        assertBalance(secondUpdates, 6);
        drainHandlers();
        assertTrue(firstUpdates.isEmpty());
        assertTrue(secondUpdates.isEmpty());

        first.disconnect();
        awaitSessions(user.getUuid(), 1);
        bank.earnPoints();
        assertBalance(secondUpdates, 7);
        drainHandlers();
        assertTrue(firstUpdates.isEmpty());
        assertTrue(secondUpdates.isEmpty());

        second.disconnect();
        awaitSessions(user.getUuid(), 0);
        bank.earnPoints();
        assertEquals(7, bank.getInitialState(user.getUuid()).balance());
        assertTrue(firstUpdates.isEmpty());
        assertTrue(secondUpdates.isEmpty());
        assertTrue(turnstile.isVerified(user.getUuid()));
        assertNoCredentialLogs(user.getUuid());
    }

    private User identity() {
        User user = new User();
        user.setUuid(UUID.randomUUID().toString());
        user.setUsername("ws-" + UUID.randomUUID());
        user.setAuthorId("author_" + UUID.randomUUID().toString().replace("-", ""));
        user.setPinHash("not-used-by-websocket-authentication");
        return users.saveAndFlush(user);
    }

    private StompSession connect(String uuid) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(
            new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient()))));
        clients.add(client);
        client.setMessageConverter(new MappingJackson2MessageConverter());
        StompHeaders headers = new StompHeaders();
        headers.add("uuid", uuid);
        StompSession session = client.connectAsync(URI.create("http://localhost:" + port + "/ws"),
            new WebSocketHttpHeaders(), headers, new StompSessionHandlerAdapter() {}).get(5, TimeUnit.SECONDS);
        sessions.add(session);
        return session;
    }

    private BankStateResponse bankBarrier(StompSession session) throws Exception {
        BlockingQueue<BankStateResponse> initial = new LinkedBlockingQueue<>();
        session.subscribe("/app/bank", handler(BankStateResponse.class, initial));
        BankStateResponse response = initial.poll(5, TimeUnit.SECONDS);
        assertNotNull(response, "Initial response is the FIFO barrier for preceding subscriptions");
        return response;
    }

    private <T> StompFrameHandler handler(Class<T> type, BlockingQueue<T> queue) {
        return new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return type; }
            @Override public void handleFrame(StompHeaders headers, Object payload) { queue.add(type.cast(payload)); }
        };
    }

    private void awaitSessions(String uuid, int count) {
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            var user = registry.getUser(uuid);
            return count == 0 ? user == null : user != null && user.getSessions().size() == count;
        });
    }

    private void assertBalance(BlockingQueue<BankStateResponse> updates, int expected) throws Exception {
        BankStateResponse response = updates.poll(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals(expected, response.balance());
    }

    private void drainHandlers() throws Exception {
        inboundExecutor.submit(() -> {}).get(5, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(5)).until(() ->
            outboundExecutor.getActiveCount() == 0 && outboundExecutor.getQueueSize() == 0);
    }

    private void assertNoCredentialLogs(String value) {
        if (value == null || value.isBlank()) return;
        for (ILoggingEvent event : logs) {
            String rendered = event.getFormattedMessage();
            if (event.getThrowableProxy() != null)
                rendered += ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy());
            assertFalse(rendered.contains(value), "Submitted credential reached combined logs in " + event.getLoggerName());
        }
    }

    private String connection(String command, String uuid) {
        return command + "\naccept-version:1.2\nhost:localhost\nheart-beat:0,0\n"
            + (uuid == null ? "" : "uuid:" + uuid + "\n") + "\n\0";
    }

    private String followOnFrames() {
        return "SEND\ndestination:/app/probe\n\n\0"
            + "SUBSCRIBE\nid:pixels\ndestination:/topic/pixels\n\n\0"
            + "SUBSCRIBE\nid:bank\ndestination:/user/queue/bank\n\n\0"
            + "SUBSCRIBE\nid:initial\ndestination:/app/bank\n\n\0";
    }

    private RawSocket raw() throws Exception {
        RawSocket socket = new RawSocket();
        socket.session = new StandardWebSocketClient().execute(socket, new WebSocketHttpHeaders(),
            URI.create("ws://localhost:" + port + "/ws/websocket")).get(5, TimeUnit.SECONDS);
        sockets.add(socket);
        return socket;
    }

    private static class RawSocket extends TextWebSocketHandler {
        final List<String> frames = new CopyOnWriteArrayList<>();
        final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        WebSocketSession session;
        void send(String payload) throws Exception { session.sendMessage(new TextMessage(payload)); }
        @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            frames.addAll(Arrays.asList(message.getPayload().split("\0")));
        }
        @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { closed.complete(status); }
    }
}
