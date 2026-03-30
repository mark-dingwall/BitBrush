package au.com.dingwall.mark.bitbrush.websocket;

import au.com.dingwall.mark.bitbrush.dto.PixelBroadcast;
import au.com.dingwall.mark.bitbrush.service.BankingService;
import au.com.dingwall.mark.bitbrush.service.TurnstileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.messaging.SessionConnectedEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Integration tests for WebSocket/STOMP real-time collaboration (RTME-01, RTME-02).
 *
 * Uses @SpringBootTest with RANDOM_PORT -- a full application context is required
 * because WebSocket tests need the actual STOMP broker running, not a mock.
 * This is the one test type where @WebMvcTest cannot help -- WebSocket messaging
 * bypasses the MVC pipeline entirely.
 *
 * Laravel equivalent: Laravel has no equivalent. Laravel Broadcasting uses Pusher
 * (external SaaS) + Laravel Echo (JS client). Testing real-time in Laravel means
 * either mocking the event dispatch ($this->expectsEvents) or running a full
 * Pusher/Redis integration test. Spring's built-in STOMP broker means we can test
 * the full pub/sub flow in-process without any external service.
 *
 * Key async patterns (no PHP equivalent):
 *   - CompletableFuture: like a JavaScript Promise -- resolves when STOMP message arrives
 *   - BlockingQueue: thread-safe message buffer for collecting multiple broadcasts
 *   - Thread.sleep(200): allows STOMP subscription to propagate before sending messages
 *     (subscriptions are async -- sending immediately after subscribe can lose the message)
 *   - .get(5, SECONDS): timeout prevents tests from hanging forever if message never arrives
 *
 * Test infrastructure:
 *   - SockJsClient + WebSocketTransport: connects via SockJS (same as browser client)
 *   - MappingJackson2MessageConverter: deserializes STOMP JSON payloads to Java records
 *   - StompSessionHandlerAdapter: minimal handler for connection lifecycle
 *   - StompFrameHandler: callback for incoming STOMP frames on subscribed topics
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(WebSocketIntegrationTest.ConnectedEventCapture.class)
class WebSocketIntegrationTest {

    /**
     * Test helper: captures the uuid extracted from SessionConnectedEvent's native headers.
     * Imported into the test application context via @Import.
     */
    static class ConnectedEventCapture {
        private final BlockingQueue<String> capturedUuids = new LinkedBlockingQueue<>();
        private final BlockingQueue<String> capturedUuidsViaConnectMessage = new LinkedBlockingQueue<>();

        @EventListener
        @SuppressWarnings("unchecked")
        public void onConnected(SessionConnectedEvent event) {
            StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());

            // Approach 1: direct getFirstNativeHeader (what the production code does)
            String uuid = accessor.getFirstNativeHeader("uuid");
            capturedUuids.offer(uuid != null ? uuid : "NULL");

            // Approach 2: extract from the embedded simpConnectMessage header
            org.springframework.messaging.Message<byte[]> connectMessage =
                    (org.springframework.messaging.Message<byte[]>) accessor.getHeader("simpConnectMessage");
            if (connectMessage != null) {
                StompHeaderAccessor connectAccessor = StompHeaderAccessor.wrap(connectMessage);
                String connectUuid = connectAccessor.getFirstNativeHeader("uuid");
                capturedUuidsViaConnectMessage.offer(connectUuid != null ? connectUuid : "NULL");
            } else {
                capturedUuidsViaConnectMessage.offer("NO_CONNECT_MSG");
            }
        }

        /**
         * Polls for the next captured uuid via direct native header (blocks up to timeout).
         */
        String pollUuid(long timeout, TimeUnit unit) throws InterruptedException {
            return capturedUuids.poll(timeout, unit);
        }

        /**
         * Polls for the next captured uuid via simpConnectMessage (blocks up to timeout).
         */
        String pollUuidViaConnectMessage(long timeout, TimeUnit unit) throws InterruptedException {
            return capturedUuidsViaConnectMessage.poll(timeout, unit);
        }

        void clear() {
            capturedUuids.clear();
            capturedUuidsViaConnectMessage.clear();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    BankingService bankingService;

    @Autowired
    ConnectedEventCapture connectedEventCapture;

    @MockitoBean
    TurnstileService turnstileService;

    private final List<StompSession> openSessions = new ArrayList<>();

    /**
     * Creates a connected StompSession via SockJS transport.
     *
     * Creates a real WebSocket connection via SockJS -- same transport the browser uses.
     * Laravel: No equivalent. Laravel Echo connects to Pusher's servers, not to the app.
     */
    private StompSession connect() throws Exception {
        WebSocketStompClient stompClient = new WebSocketStompClient(
                new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient())))
        );
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
        StompSession session = stompClient
                .connect("http://localhost:{port}/ws", new StompSessionHandlerAdapter() {}, port)
                .get(5, TimeUnit.SECONDS);
        openSessions.add(session);
        return session;
    }

    /**
     * Creates a connected StompSession that sends a "uuid" STOMP CONNECT header.
     * Uses the connectAsync overload that accepts StompHeaders.
     */
    private StompSession connectWithUuid(String uuid) throws Exception {
        WebSocketStompClient stompClient = new WebSocketStompClient(
                new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient())))
        );
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("uuid", uuid);
        StompSession session = stompClient
                .connectAsync(
                        URI.create("http://localhost:" + port + "/ws"),
                        new WebSocketHttpHeaders(),
                        connectHeaders,
                        new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
        openSessions.add(session);
        return session;
    }

    // WebSocket sessions are persistent (unlike HTTP request/response).
    // Must explicitly disconnect to avoid session leaks between tests.
    // Laravel: No equivalent -- HTTP tests are stateless by nature.
    @BeforeEach
    void allowTurnstile() {
        when(turnstileService.verifyAndRemember(any(), any())).thenReturn(true);
        when(turnstileService.isVerified(any())).thenReturn(true);
        connectedEventCapture.clear();
    }

    @AfterEach
    void disconnectAll() {
        for (StompSession session : openSessions) {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
        openSessions.clear();
        // Clean up any manually registered bank entries from pixel broadcast test
        bankingService.onUserDisconnect("ws-test-uuid-pixel");
        connectedEventCapture.clear();
    }

    /**
     * RTME-01: Pixel broadcast delivered to STOMP subscriber.
     *
     * Given: A STOMP client is connected and subscribed to /topic/pixels
     * When:  A pixel is placed via POST /api/pixels
     * Then:  The subscriber receives a JSON message {x, y, color}
     *        where color is the resolved hex string (not palette index),
     *        within 5 seconds
     */
    @Test
    void pixelBroadcastDeliveredToSubscriber() throws Exception {
        // Given: register a user so pixel placement is authorized
        String userUuid = "ws-test-uuid-pixel";
        String wsSession = "ws-test-session-pixel";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        restTemplate.postForEntity("/api/users",
                new HttpEntity<>("""
                        {"uuid": "%s", "username": "wstester"}
                        """.formatted(userUuid), headers),
                Void.class);

        // Register with BankingService so pixel placement has a valid bank entry.
        // The STOMP client below does not send a uuid header (test infrastructure
        // doesn't support custom CONNECT headers easily), so we initialize manually.
        bankingService.onUserConnect(userUuid, wsSession);

        // CompletableFuture acts like a JavaScript Promise -- complete() resolves it.
        // The StompFrameHandler's handleFrame callback completes the future when a message arrives.
        // .get(5, SECONDS) blocks the test thread until the future resolves or times out.
        // Laravel: Event::assertDispatched() checks synchronously; here we must wait for async delivery.

        // Given: a STOMP client connected and subscribed to /topic/pixels
        CompletableFuture<PixelBroadcast> received = new CompletableFuture<>();
        StompSession session = connect();
        session.subscribe("/topic/pixels", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return PixelBroadcast.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.complete((PixelBroadcast) payload);
            }
        });

        // Allow subscription to register
        Thread.sleep(200);

        // When: POST /api/pixels with paletteIndex 0 -> first color in palette
        restTemplate.postForEntity("/api/pixels",
                new HttpEntity<>("""
                        {
                          "pixels": [{"x": 5, "y": 10}],
                          "paletteIndex": 0,
                          "authorUuid": "%s"
                        }
                        """.formatted(userUuid), headers),
                Void.class);

        // Then: subscriber receives {x: 5, y: 10, color: <resolved hex>} within 5 seconds
        PixelBroadcast broadcast = received.get(5, TimeUnit.SECONDS);
        assertThat(broadcast).isNotNull();
        assertThat(broadcast.x()).isEqualTo(5);
        assertThat(broadcast.y()).isEqualTo(10);
        assertThat(broadcast.color()).matches("#[0-9a-fA-F]{6}");
    }

    /**
     * RTME-02: User count broadcast on connect and disconnect.
     *
     * Given: A STOMP client subscribes to /topic/users/count
     * When:  A second STOMP client connects, then disconnects
     * Then:  The subscriber receives count updates:
     *        - count increases when second client connects
     *        - count decreases when second client disconnects
     *
     * Note on timing: the count broadcast for client A's own connection fires
     * before the subscription frame arrives at the broker, so it may be missed.
     * The test waits for subscription to settle before connecting client B,
     * then verifies that B's connect and disconnect each trigger a count update
     * in the correct direction.
     */
    @Test
    void userCountBroadcastOnConnectAndDisconnect() throws Exception {
        // BlockingQueue collects multiple messages over time (unlike CompletableFuture which takes only one).
        // poll(5, SECONDS) blocks until a message arrives or times out -- no busy-wait loop needed.
        // This pattern tests a sequence of events: connect -> count increases, disconnect -> count decreases.

        // Given: client A connected and subscribed to /topic/users/count
        BlockingQueue<Integer> counts = new LinkedBlockingQueue<>();
        StompSession clientA = connect();
        clientA.subscribe("/topic/users/count", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Integer.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                counts.offer((Integer) payload);
            }
        });

        // Allow subscription to propagate to broker before observing events
        Thread.sleep(300);

        // Drain any stale count messages that arrived before subscription settled
        counts.clear();

        // When: client B connects -> count should increase
        StompSession clientB = connect();
        Integer countAfterBConnected = counts.poll(5, TimeUnit.SECONDS);
        assertThat(countAfterBConnected).isNotNull().isGreaterThanOrEqualTo(2);

        // When: client B disconnects -> count should decrease
        clientB.disconnect();
        openSessions.remove(clientB);
        Integer countAfterBDisconnected = counts.poll(5, TimeUnit.SECONDS);
        assertThat(countAfterBDisconnected).isNotNull().isLessThan(countAfterBConnected);
    }

    /**
     * Proves that getFirstNativeHeader("uuid") returns null on SessionConnectedEvent's
     * CONNECT_ACK message, but the uuid IS available via the embedded simpConnectMessage.
     *
     * This test documents the Spring framework behavior:
     * - SessionConnectedEvent wraps the broker's CONNECT_ACK, not the client's CONNECT frame
     * - The CONNECT_ACK has no client native headers
     * - The original CONNECT message is embedded in the "simpConnectMessage" header
     */
    @Test
    void sessionConnectedEventPreservesClientNativeHeaders() throws Exception {
        String testUuid = "header-probe-uuid-" + System.nanoTime();
        connectWithUuid(testUuid);

        // Wait for the SessionConnectedEvent to fire and our capture listener to record it
        String directCapture = connectedEventCapture.pollUuid(5, TimeUnit.SECONDS);
        String viaConnectMessage = connectedEventCapture.pollUuidViaConnectMessage(5, TimeUnit.SECONDS);

        // Direct getFirstNativeHeader on the CONNECTED/CONNECT_ACK message returns null.
        assertThat(directCapture)
                .as("getFirstNativeHeader('uuid') on CONNECT_ACK message is NULL (native headers not preserved)")
                .isEqualTo("NULL");

        // But extracting the original CONNECT message from simpConnectMessage header DOES work.
        assertThat(viaConnectMessage)
                .as("getFirstNativeHeader('uuid') via simpConnectMessage should return the client's uuid")
                .isNotNull()
                .isNotEqualTo("NULL")
                .isEqualTo(testUuid);
    }
}
