package au.com.dingwall.mark.bitbrush.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** RFC 7807 responses and constant diagnostics; exception text can contain credentials. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(UserNotFoundException.class)
    public ProblemDetail handleUserNotFound(UserNotFoundException ex) {
        log.debug("User lookup failed");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "User not found");
        problem.setTitle("User Not Found");
        return problem;
    }

    @ExceptionHandler(DuplicateIdentityException.class)
    public ProblemDetail handleDuplicateIdentity(DuplicateIdentityException ex) {
        log.debug("Identity creation conflict");
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Identity already exists");
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail handleInvalidCredentials(InvalidCredentialsException ex) {
        log.debug("Identity recovery failed");
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid username or PIN");
    }

    @ExceptionHandler(RecoveryThrottledException.class)
    public ResponseEntity<ProblemDetail> handleRecoveryThrottled(RecoveryThrottledException ex) {
        return recoveryUnavailable(ex.retryAfterSeconds());
    }

    @ExceptionHandler(RecoveryCapacityException.class)
    public ResponseEntity<ProblemDetail> handleRecoveryCapacity(RecoveryCapacityException ex) {
        return recoveryUnavailable(ex.retryAfterSeconds());
    }

    private ResponseEntity<ProblemDetail> recoveryUnavailable(long retryAfter) {
        log.debug("Identity recovery temporarily unavailable");
        return retryable(HttpStatus.TOO_MANY_REQUESTS, "Identity recovery is temporarily unavailable", retryAfter);
    }

    @ExceptionHandler(PinCapacityException.class)
    public ResponseEntity<ProblemDetail> handlePinCapacity(PinCapacityException ex) {
        log.debug("PIN processing temporarily unavailable");
        return retryable(HttpStatus.SERVICE_UNAVAILABLE, "PIN credential processing is temporarily unavailable", ex.retryAfterSeconds());
    }

    @ExceptionHandler(InvalidPinException.class)
    public ProblemDetail handleInvalidPin(InvalidPinException ex) {
        log.debug("Invalid PIN input");
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid PIN");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.error("Persistence request failed");
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Request could not be completed");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        log.debug("Invalid request");
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request");
    }

    @ExceptionHandler(TurnstileException.class)
    public ProblemDetail handleTurnstileFailure(TurnstileException ex) {
        log.debug("Turnstile verification failed");
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Turnstile verification failed");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpectedFailure(Exception ex) {
        log.error("Unexpected request failure");
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Request could not be completed");
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<ProblemDetail> handleInsufficientBalance(InsufficientBalanceException ex) {
        log.debug("Insufficient placement balance");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.PAYMENT_REQUIRED, "Insufficient placement balance");
        problem.setTitle("Insufficient Balance");
        problem.setProperty("retryAfterSeconds", ex.getRetryAfterSeconds());
        problem.setProperty("balance", 0);
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
            .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds())).body(problem);
    }

    private ResponseEntity<ProblemDetail> retryable(HttpStatus status, String detail, long retryAfter) {
        return ResponseEntity.status(status).header("Retry-After", Long.toString(retryAfter))
            .body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
