package au.com.dingwall.mark.bitbrush.config;

import au.com.dingwall.mark.bitbrush.repository.UserRepository;
import au.com.dingwall.mark.bitbrush.service.TurnstileService;
import au.com.dingwall.mark.bitbrush.validation.CanonicalUuidValidator;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;

/** Validates private connection credentials before any decoded frame is dispatched. */
@Component
public class StompAuthenticationInterceptor implements ChannelInterceptor {
    private final UserRepository users;
    private final TurnstileService turnstile;

    public StompAuthenticationInterceptor(UserRepository users, TurnstileService turnstile) {
        this.users = users;
        this.turnstile = turnstile;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getMessageType() == SimpMessageType.HEARTBEAT) return message;

        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
            String uuid = accessor.getFirstNativeHeader("uuid");
            if (!CanonicalUuidValidator.isCanonical(uuid)) throw invalidIdentity();
            boolean exists;
            try {
                exists = users.existsById(uuid);
            } catch (RuntimeException failure) {
                // Repository diagnostics can contain credentials; do not retain the cause.
                throw invalidIdentity();
            }
            if (!exists) throw invalidIdentity();
            // Spring's user-change callback records this identity synchronously for
            // subsequent frames, including frames decoded from the same socket message.
            accessor.setUser(new StompPrincipal(uuid));
            turnstile.markVerified(uuid);
        } else if (!(accessor.getUser() instanceof StompPrincipal)) {
            throw invalidIdentity();
        }
        return message;
    }

    private MessagingException invalidIdentity() {
        return new MessagingException("Invalid connection identity");
    }

    private record StompPrincipal(String name) implements Principal {
        @Override
        public String getName() { return name; }
    }
}
