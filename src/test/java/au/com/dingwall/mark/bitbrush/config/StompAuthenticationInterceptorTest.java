package au.com.dingwall.mark.bitbrush.config;

import au.com.dingwall.mark.bitbrush.repository.UserRepository;
import au.com.dingwall.mark.bitbrush.service.TurnstileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StompAuthenticationInterceptorTest {
    private static final String UUID = "8c7829fa-6793-4f17-b5de-af7432b6b4a8";
    private final UserRepository users = mock(UserRepository.class);
    private final TurnstileService turnstile = mock(TurnstileService.class);

    private ChannelInterceptor interceptor() {
        return new StompAuthenticationInterceptor(users, turnstile);
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"CONNECT", "STOMP"})
    void knownPrivateUuidIsAuthenticatedAndVerifiedSynchronously(StompCommand command) {
        when(users.existsById(UUID)).thenReturn(true);
        StompHeaderAccessor headers = headers(command, UUID);
        Message<byte[]> message = message(headers);
        assertSame(message, interceptor().preSend(message, null));
        assertNotNull(headers.getUser());
        assertEquals(UUID, headers.getUser().getName());
        verify(turnstile).markVerified(UUID);
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"CONNECT", "STOMP"})
    void invalidIdentitiesAreRejectedWithoutVerification(StompCommand command) {
        for (String value : new String[]{null, "", "   ", "broken-uuid", "p_abcdefghijklmnopqrstuv", UUID,
                "8C7829FA-6793-4F17-B5DE-AF7432B6B4A8"}) {
            StompHeaderAccessor headers = headers(command, value);
            MessagingException error = assertThrows(MessagingException.class,
                () -> interceptor().preSend(message(headers), null));
            assertEquals("Invalid connection identity", error.getMessage());
            assertNull(headers.getUser());
        }
        verifyNoInteractions(turnstile);
        verify(users).existsById(UUID);
        verifyNoMoreInteractions(users);
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"CONNECT", "STOMP"})
    void repositoryFailureIsGenericAndDoesNotVerify(StompCommand command) {
        when(users.existsById(UUID)).thenThrow(new IllegalStateException("private repository data " + UUID));
        MessagingException error = assertThrows(MessagingException.class,
            () -> interceptor().preSend(message(headers(command, UUID)), null));
        assertEquals("Invalid connection identity", error.getMessage());
        assertNull(error.getCause());
        verifyNoInteractions(turnstile);
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"SEND", "SUBSCRIBE", "UNSUBSCRIBE", "ACK", "NACK", "BEGIN", "COMMIT", "ABORT", "DISCONNECT"})
    void laterClientCommandsRequirePropagatedAuthenticatedPrincipal(StompCommand command) {
        assertThrows(MessagingException.class, () -> interceptor().preSend(message(headers(command, UUID)), null));
        verifyNoInteractions(users, turnstile);
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"SEND", "SUBSCRIBE", "UNSUBSCRIBE", "ACK", "NACK", "BEGIN", "COMMIT", "ABORT", "DISCONNECT"})
    void propagatedPrincipalAuthorizesLaterClientCommandsWithoutRepeatingAuthentication(StompCommand command) {
        when(users.existsById(UUID)).thenReturn(true);
        StompHeaderAccessor connect = headers(StompCommand.CONNECT, UUID);
        ChannelInterceptor authentication = interceptor();
        authentication.preSend(message(connect), null);
        StompHeaderAccessor later = headers(command, null);
        later.setUser(connect.getUser());
        Message<byte[]> frame = message(later);
        assertSame(frame, authentication.preSend(frame, null));
        verify(users).existsById(UUID);
        verify(turnstile).markVerified(UUID);
        verifyNoMoreInteractions(users, turnstile);
    }

    @Test
    void heartbeatRequiresNoPrincipal() {
        Message<byte[]> heartbeat = message(StompHeaderAccessor.createForHeartbeat());
        assertSame(heartbeat, interceptor().preSend(heartbeat, null));
        verifyNoInteractions(users, turnstile);
    }

    private StompHeaderAccessor headers(StompCommand command, String value) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(command);
        if (value != null) headers.setNativeHeader("uuid", value);
        headers.setLeaveMutable(true);
        return headers;
    }

    private Message<byte[]> message(StompHeaderAccessor headers) {
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}
