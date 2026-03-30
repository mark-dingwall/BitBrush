package au.com.dingwall.mark.bitbrush.websocket;

import au.com.dingwall.mark.bitbrush.service.BankingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks connected WebSocket sessions and broadcasts the current session count.
 *
 * Uses a Set<String> of session IDs (via ConcurrentHashMap.newKeySet()) rather
 * than AtomicInteger to ensure idempotent disconnect handling -- Spring may
 * publish SessionDisconnectEvent multiple times for the same session, and
 * Set.remove() is a safe no-op for already-removed IDs.
 *
 * Publishes count changes to /topic/users/count.
 */
@Component
public class WebSocketEventListener {

    private static final Logger log = LoggerFactory.getLogger(WebSocketEventListener.class);

    private final Set<String> sessions = ConcurrentHashMap.newKeySet();
    // sessionId -> uuid: for reverse lookup on disconnect (needed to call bankingService.onUserDisconnect)
    private final ConcurrentHashMap<String, String> sessionToUuid = new ConcurrentHashMap<>();
    private final SimpMessagingTemplate messagingTemplate;
    private final BankingService bankingService;

    public WebSocketEventListener(SimpMessagingTemplate messagingTemplate,
                                   BankingService bankingService) {
        this.messagingTemplate = messagingTemplate;
        this.bankingService = bankingService;
    }

    @EventListener
    @SuppressWarnings("unchecked")
    public void handleConnect(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();

        // SessionConnectedEvent wraps the broker's CONNECT_ACK message, not the client's
        // CONNECT frame. The CONNECT_ACK has no native headers from the client.
        // The original CONNECT message is embedded under the "simpConnectMessage" header.
        String uuid = null;
        Message<byte[]> connectMessage =
                (Message<byte[]>) accessor.getHeader("simpConnectMessage");
        if (connectMessage != null) {
            StompHeaderAccessor connectAccessor = StompHeaderAccessor.wrap(connectMessage);
            uuid = connectAccessor.getFirstNativeHeader("uuid");
        }
        sessions.add(sessionId);
        if (uuid != null && !uuid.isBlank()) {
            sessionToUuid.put(sessionId, uuid);
            bankingService.onUserConnect(uuid, sessionId);
        }
        broadcastCount();
        log.debug("WebSocket connected: sessionId={}, uuid={}, total={}", sessionId, uuid, sessions.size());
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        sessions.remove(sessionId);
        String uuid = sessionToUuid.remove(sessionId);
        if (uuid != null) {
            bankingService.onUserDisconnect(uuid);
        }
        broadcastCount();
        log.debug("WebSocket disconnected: sessionId={}, uuid={}, total={}", sessionId, uuid, sessions.size());
    }

    /**
     * Returns the current number of connected WebSocket sessions.
     * Used by UserCountController to respond to subscribe requests with the
     * current count, so newly connected clients don't miss their own join broadcast.
     */
    public int getCount() {
        return sessions.size();
    }

    private void broadcastCount() {
        messagingTemplate.convertAndSend("/topic/users/count", sessions.size());
    }
}
