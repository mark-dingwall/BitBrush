package au.com.dingwall.mark.bitbrush.service;

import au.com.dingwall.mark.bitbrush.config.BitbrushProperties;
import au.com.dingwall.mark.bitbrush.dto.BankStateResponse;
import au.com.dingwall.mark.bitbrush.exception.InsufficientBalanceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages placement point banking for all connected users.
 *
 * Banking state is held entirely in memory — no database backing. Points reset on
 * server restart. This is intentional: the goal is to demonstrate JVM concurrency
 * primitives (ConcurrentHashMap.compute(), @Scheduled) that have no equivalent in
 * PHP's single-threaded-per-request model.
 *
 * Thread safety:
 * - bankMap: ConcurrentHashMap&lt;uuid, BankState&gt; — compute() used for all mutations
 * - uuidToSessionId: ConcurrentHashMap&lt;uuid, sessionId&gt; — tracks connected users
 *   (only connected UUIDs earn points; disconnect removes entry)
 */
@Service
public class BankingService {

    private static final Logger log = LoggerFactory.getLogger(BankingService.class);

    /**
     * Immutable bank state per user. Replaced atomically via ConcurrentHashMap.compute().
     * Using a record ensures no partial mutation is possible between reads.
     */
    private record BankState(int balance, Instant lastEarnedAt) {
        BankState withBalance(int newBalance) {
            return new BankState(newBalance, this.lastEarnedAt);
        }
        BankState withLastEarnedAt(Instant time) {
            return new BankState(this.balance, time);
        }
    }

    // uuid -> bank state (all users who have ever connected)
    private final ConcurrentHashMap<String, BankState> bankMap = new ConcurrentHashMap<>();
    // uuid -> sessionId (connected users only; disconnect removes entry — freeze-on-disconnect)
    private final ConcurrentHashMap<String, String> uuidToSessionId = new ConcurrentHashMap<>();

    private final SimpMessagingTemplate messagingTemplate;
    private final BitbrushProperties bitbrushProperties;

    public BankingService(SimpMessagingTemplate messagingTemplate,
                          BitbrushProperties bitbrushProperties) {
        this.messagingTemplate = messagingTemplate;
        this.bitbrushProperties = bitbrushProperties;
    }

    /**
     * Called by WebSocketEventListener when a user connects.
     * Initializes bank entry at startingBalance (from config) for new users; retains existing
     * balance for reconnecting users (resume where left off).
     */
    public void onUserConnect(String userUuid, String sessionId) {
        uuidToSessionId.put(userUuid, sessionId);
        bankMap.computeIfAbsent(userUuid, k -> new BankState(bitbrushProperties.placement().startingBalance(), Instant.now()));
        log.debug("User connected: uuid={}, sessionId={}, balance={}",
            userUuid, sessionId, bankMap.get(userUuid).balance());
    }

    /**
     * Called by WebSocketEventListener when a user disconnects.
     * Removes from uuidToSessionId so the earn task skips this user (freeze-on-disconnect).
     * BankState is retained in bankMap for resumption on reconnect.
     */
    public void onUserDisconnect(String userUuid) {
        uuidToSessionId.remove(userUuid);
        log.debug("User disconnected: uuid={} — earn task will skip until reconnect", userUuid);
    }

    /**
     * Atomically deducts 1 point from the user's balance.
     * Throws InsufficientBalanceException if balance is 0 — callers should NOT suppress.
     *
     * CRITICAL: Uses compute() to ensure the check-and-deduct is atomic.
     * Separate get() + put() would create a race window where two concurrent requests
     * both read balance=1, both see it as &gt; 0, and both deduct — spending 0 points
     * while placing 2 pixels.
     */
    public void deductPoint(String userUuid) {
        int earnRate = bitbrushProperties.placement().earnRateSeconds();
        boolean[] deducted = {false};
        bankMap.compute(userUuid, (uuid, state) -> {
            if (state == null || state.balance() <= 0) {
                return state; // no change
            }
            deducted[0] = true;
            return state.withBalance(state.balance() - 1);
        });
        if (!deducted[0]) {
            throw new InsufficientBalanceException(earnRate);
        }
        // Push updated balance AFTER compute() returns (must not call sendToUser inside compute)
        pushBankState(userUuid);
        log.debug("Point deducted: uuid={}, new balance={}",
            userUuid, bankMap.getOrDefault(userUuid, new BankState(0, Instant.now())).balance());
    }

    /**
     * Atomically deducts up to {@code count} points from the user's balance.
     * Returns the number of points actually deducted (may be less than {@code count}
     * if balance is insufficient). Returns 0 if no balance at all.
     *
     * Unlike {@link #deductPoint(String)}, this does NOT throw InsufficientBalanceException
     * on partial deduction — the caller decides how to handle partial results.
     * This supports drag-to-place partial placement per the "stop placing silently" decision.
     */
    public int deductPoints(String userUuid, int count) {
        int[] deducted = {0};
        bankMap.compute(userUuid, (uuid, state) -> {
            if (state == null || state.balance() <= 0) return state;
            int actual = Math.min(count, state.balance());
            deducted[0] = actual;
            return state.withBalance(state.balance() - actual);
        });
        if (deducted[0] > 0) {
            pushBankState(userUuid);
        }
        return deducted[0];
    }

    /**
     * Returns the current bank state for a given user UUID.
     * Used by BankController's @SubscribeMapping to deliver initial state to new subscribers.
     */
    public BankStateResponse getInitialState(String userUuid) {
        if (userUuid == null) {
            log.debug("getInitialState: no uuid provided");
            int maxBanked = bitbrushProperties.placement().maxBanked();
            int earnRate = bitbrushProperties.placement().earnRateSeconds();
            return new BankStateResponse(bitbrushProperties.placement().startingBalance(), maxBanked, earnRate);
        }
        BankState state = bankMap.get(userUuid);
        if (state == null) {
            int maxBanked = bitbrushProperties.placement().maxBanked();
            int earnRate = bitbrushProperties.placement().earnRateSeconds();
            return new BankStateResponse(bitbrushProperties.placement().startingBalance(), maxBanked, earnRate);
        }
        return buildResponse(state);
    }

    /**
     * Background task: earn 1 point per connected user per tick.
     *
     * Fires every earnRateSeconds (property value * 1000ms).
     * NOTE: fixedDelayString in milliseconds — "${bitbrush.placement.earn-rate-seconds}000"
     * appends "000" to the property string value (e.g. "3" -&gt; "3000" = 3 seconds).
     *
     * Only connected users earn (uuidToSessionId keySet = connected UUIDs).
     * Users already at maxBanked are skipped.
     * STOMP push fires only when balance actually changes.
     */
    @Scheduled(fixedDelayString = "${bitbrush.placement.earn-rate-seconds}000")
    public void earnPoints() {
        int maxBanked = bitbrushProperties.placement().maxBanked();
        uuidToSessionId.keySet().forEach(uuid -> {
            boolean[] earned = {false};
            bankMap.compute(uuid, (k, state) -> {
                if (state == null || state.balance() >= maxBanked) return state;
                earned[0] = true;
                return state.withBalance(state.balance() + 1).withLastEarnedAt(Instant.now());
            });
            if (earned[0]) {
                log.debug("Point earned: uuid={}", uuid);
                pushBankState(uuid);
            }
        });
    }

    private void pushBankState(String userUuid) {
        if (!uuidToSessionId.containsKey(userUuid)) return;
        BankState state = bankMap.get(userUuid);
        if (state == null) return;
        BankStateResponse response = buildResponse(state);
        // Use UUID (the Principal name set by WebSocketConfig's ChannelInterceptor)
        // NOT the session ID — SimpUserRegistry tracks users by Principal name.
        messagingTemplate.convertAndSendToUser(userUuid, "/queue/bank", response);
        log.debug("Pushed bank state: uuid={}, balance={}", userUuid, response.balance());
    }

    private BankStateResponse buildResponse(BankState state) {
        int maxBanked = bitbrushProperties.placement().maxBanked();
        int earnRate = bitbrushProperties.placement().earnRateSeconds();
        long elapsed = Duration.between(state.lastEarnedAt(), Instant.now()).toSeconds();
        int secondsUntil = Math.max(0, (int) (earnRate - elapsed));
        return new BankStateResponse(state.balance(), maxBanked, secondsUntil);
    }
}
