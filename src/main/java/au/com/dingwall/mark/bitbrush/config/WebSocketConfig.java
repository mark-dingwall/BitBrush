package au.com.dingwall.mark.bitbrush.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP/WebSocket broker configuration.
 *
 * Exposes a SockJS-enabled endpoint at /ws.
 * Simple in-memory broker handles /topic and /queue destinations.
 * Application destination prefix /app for @MessageMapping and @SubscribeMapping.
 *
 * A ChannelInterceptor authenticates the private "uuid" STOMP connection header.
 * Without a Principal, Spring's SimpUserRegistry ignores the session and
 * convertAndSendToUser() silently drops messages — breaking per-user /queue pushes.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);
    private final StompAuthenticationInterceptor authentication;

    public WebSocketConfig(StompAuthenticationInterceptor authentication) {
        this.authentication = authentication;
    }

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
        // One worker preserves handler FIFO after synchronous authentication.
        // Do not enable preserveReceiveOrder: Spring 6.2's ordered decorator
        // catches interceptor failures and logs the full credential-bearing frame.
        registration.taskExecutor().corePoolSize(1).maxPoolSize(1);
        registration.interceptors(authentication);
    }
}
