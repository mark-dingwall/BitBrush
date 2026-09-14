package au.com.dingwall.mark.bitbrush.exception;

public class RecoveryCapacityException extends RuntimeException {

    private final long retryAfterSeconds;

    public RecoveryCapacityException(long retryAfterSeconds) {
        super("Identity recovery is temporarily unavailable");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
