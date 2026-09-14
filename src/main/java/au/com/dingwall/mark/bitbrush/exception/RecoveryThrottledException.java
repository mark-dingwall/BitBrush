package au.com.dingwall.mark.bitbrush.exception;

public class RecoveryThrottledException extends RuntimeException {

    private final long retryAfterSeconds;

    public RecoveryThrottledException(long retryAfterSeconds) {
        super("Identity recovery is temporarily unavailable");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
