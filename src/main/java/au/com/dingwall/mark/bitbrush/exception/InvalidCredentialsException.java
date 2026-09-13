package au.com.dingwall.mark.bitbrush.exception;

public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() { super("Invalid username or PIN"); }
}
