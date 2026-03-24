package au.com.dingwall.mark.bitbrush.dto;

/**
 * Response DTO sent to clients via STOMP push (/user/queue/bank) and
 * returned by @SubscribeMapping /bank for the initial state delivery.
 *
 * Immutable record — all fields set at construction; no setters.
 */
public record BankStateResponse(
    int balance,
    int maxBalance,
    int secondsUntilNextPoint
) {}
