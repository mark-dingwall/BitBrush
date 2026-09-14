package au.com.dingwall.mark.bitbrush.exception;

public class InvalidPinException extends RuntimeException {

    public InvalidPinException() {
        super("PIN must contain exactly four valid Unicode code points");
    }
}
