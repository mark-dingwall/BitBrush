package au.com.dingwall.mark.bitbrush.websocket;

import au.com.dingwall.mark.bitbrush.config.TurnstileProperties;
import au.com.dingwall.mark.bitbrush.service.BankingService;
import au.com.dingwall.mark.bitbrush.service.TurnstileService;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSocketEventListenerTest {
    @Test
    void presenceUsesEventPrincipalAndDisconnectRetainsVerification() {
        var messages = mock(SimpMessagingTemplate.class);
        var bank = mock(BankingService.class);
        var turnstile = new TurnstileService(new TurnstileProperties("site", "secret"), RestClient.builder());
        var listener = new WebSocketEventListener(messages, bank);
        String uuid = "8c7829fa-6793-4f17-b5de-af7432b6b4a8";
        turnstile.markVerified(uuid);
        var headers = StompHeaderAccessor.create(StompCommand.CONNECTED);
        headers.setSessionId("session-one");
        var message = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        var connected = new SessionConnectedEvent(this, message, () -> uuid);
        listener.handleConnect(connected);
        listener.handleConnect(connected);
        assertEquals(1, listener.getCount());
        verify(bank).ensureBank(uuid);
        verify(messages).convertAndSend("/topic/users/count", 1);

        var disconnected = new SessionDisconnectEvent(this, message, "session-one", CloseStatus.NORMAL, () -> uuid);
        listener.handleDisconnect(disconnected);
        listener.handleDisconnect(disconnected);
        assertEquals(0, listener.getCount());
        assertTrue(turnstile.isVerified(uuid));
        verify(messages).convertAndSend("/topic/users/count", 0);
    }
}
