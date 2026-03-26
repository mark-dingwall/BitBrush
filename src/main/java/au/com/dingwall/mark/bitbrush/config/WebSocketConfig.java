package au.com.dingwall.mark.bitbrush.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.security.Principal;

/**
 * STOMP/WebSocket broker configuration.
 *
 * Exposes a SockJS-enabled endpoint at /ws.
 * Simple in-memory broker handles /topic and /queue destinations.
 * Application destination prefix /app for @MessageMapping and @SubscribeMapping.
 *
 * A ChannelInterceptor assigns a Principal from the "uuid" STOMP CONNECT header.
 * Without a Principal, Spring's SimpUserRegistry ignores the session and
 * convertAndSendToUser() silently drops messages — breaking per-user /queue pushes.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
               .setAllowedOriginPatterns("https://*.github.io", "https://*.fly.dev", "https://mark.dingwall.com.au", "http://localhost:[*]")
               .withSockJS();
        log.info("STOMP endpoint registered at /ws (SockJS enabled)");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        ThreadPoolTaskScheduler heartbeatScheduler = new ThreadPoolTaskScheduler();
        heartbeatScheduler.setPoolSize(1);
        heartbeatScheduler.setThreadNamePrefix("ws-heartbeat-");
        heartbeatScheduler.initialize();

        config.enableSimpleBroker("/topic", "/queue")
              .setTaskScheduler(heartbeatScheduler)
              .setHeartbeatValue(new long[]{10000, 10000});
        config.setApplicationDestinationPrefixes("/app");
        log.info("Simple message broker enabled for /topic and /queue; application prefix /app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                    String uuid = accessor.getFirstNativeHeader("uuid");
                    if (uuid != null && !uuid.isBlank()) {
                        accessor.setUser(new StompPrincipal(uuid));
                        log.debug("Assigned Principal '{}' from STOMP CONNECT uuid header", uuid);
                    }
                }
                return message;
            }
        });
    }

    /**
     * Minimal Principal backed by the client's UUID.
     * Enables SimpUserRegistry to track the session so that
     * convertAndSendToUser(uuid, ...) resolves correctly.
     */
    private record StompPrincipal(String name) implements Principal {
        @Override
        public String getName() {
            return name;
        }
    }
}
