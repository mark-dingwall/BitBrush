package au.com.dingwall.mark.bitbrush.config;

import au.com.dingwall.mark.bitbrush.repository.UserRepository;
import au.com.dingwall.mark.bitbrush.service.TurnstileService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StompDestinationAuthorizationTest {
    private final UserRepository users = mock(UserRepository.class);
    private final StompAuthenticationInterceptor interceptor =
        new StompAuthenticationInterceptor(users, mock(TurnstileService.class));

    private Principal authenticate() {
        String uuid = "8c7829fa-6793-4f17-b5de-af7432b6b4a8";
        when(users.existsById(uuid)).thenReturn(true);
        StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.CONNECT);
        headers.setNativeHeader("uuid", uuid);
        headers.setLeaveMutable(true);
        interceptor.preSend(MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()), null);
        return headers.getUser();
    }

    private Message<byte[]> frame(StompCommand command, String destination) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(command);
        headers.setUser(authenticate());
        headers.setDestination(destination);
        headers.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/topic/pixels", "/queue/bank", "/user/queue/bank", "/topic", "/queue", "/user", "/application/pixels", "/app"})
    void rejectsBrokerDestinations(String destination) {
        Message<byte[]> message = frame(StompCommand.SEND, destination);
        assertThrows(MessagingException.class, () -> interceptor.preSend(message, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/app/pixels", "/app/bank"})
    void permitsApplicationSends(String destination) {
        Message<byte[]> message = frame(StompCommand.SEND, destination);
        assertSame(message, interceptor.preSend(message, null));
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"SUBSCRIBE", "UNSUBSCRIBE", "ACK", "NACK", "BEGIN", "COMMIT", "ABORT", "DISCONNECT"})
    void preservesOtherAuthenticatedCommands(StompCommand command) {
        Message<byte[]> message = frame(command, "/topic/pixels");
        assertSame(message, interceptor.preSend(message, null));
    }
}
