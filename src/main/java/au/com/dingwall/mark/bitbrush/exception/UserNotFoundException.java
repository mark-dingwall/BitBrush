package au.com.dingwall.mark.bitbrush.exception;

/**
 * Thrown when an authorUuid supplied in a pixel placement request
 * does not match any registered user.
 */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(String uuid) {
        super("User not found: " + uuid);
    }
}
