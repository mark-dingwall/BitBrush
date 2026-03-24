package au.com.dingwall.mark.bitbrush.websocket;

import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;

/**
 * Handles STOMP subscribe events so newly connected clients can request
 * the current user count immediately after subscribing.
 *
 * The race condition without this: SessionConnectEvent fires and broadcasts
 * the count to /topic/users/count BEFORE the client's onConnect handler
 * has set up its /topic/users/count subscription -- so the new client
 * misses its own join broadcast and shows "connecting..." until the next
 * connect/disconnect event.
 *
 * Fix: the client also subscribes to /app/users/count inside onConnect.
 * @SubscribeMapping intercepts that subscription and returns the current
 * count directly to the subscribing client (no broadcast). The response
 * is delivered to the client's /app/users/count subscription frame.
 */
@Controller
public class UserCountController {

    private final WebSocketEventListener eventListener;

    public UserCountController(WebSocketEventListener eventListener) {
        this.eventListener = eventListener;
    }

    /**
     * Responds to a SUBSCRIBE frame for /app/users/count.
     * Return value is sent directly to the subscribing client only.
     */
    @SubscribeMapping("/users/count")
    public int handleUserCountSubscribe() {
        return eventListener.getCount();
    }
}
