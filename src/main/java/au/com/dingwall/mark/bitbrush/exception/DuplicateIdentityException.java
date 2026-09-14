package au.com.dingwall.mark.bitbrush.exception;

public class DuplicateIdentityException extends RuntimeException {
    public DuplicateIdentityException() { super("Identity already exists"); }
}
