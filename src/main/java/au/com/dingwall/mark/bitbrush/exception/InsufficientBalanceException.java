package au.com.dingwall.mark.bitbrush.exception;

/**
 * Thrown by BankingService.deductPoint() when a user's placement point balance is zero.
 *
 * Carries retryAfterSeconds so GlobalExceptionHandler can set the Retry-After HTTP header
 * and embed the value in the ProblemDetail response body for clients.
 */
public class InsufficientBalanceException extends RuntimeException {

    private final int retryAfterSeconds;

    public InsufficientBalanceException(int retryAfterSeconds) {
        super("Insufficient placement balance");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
