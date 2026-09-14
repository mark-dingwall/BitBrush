package au.com.dingwall.mark.bitbrush.exception;

public class PinCapacityException extends RuntimeException {

    private final int retryAfterSeconds;

    public PinCapacityException(int retryAfterSeconds) {
        super("PIN credential processing capacity is unavailable");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
