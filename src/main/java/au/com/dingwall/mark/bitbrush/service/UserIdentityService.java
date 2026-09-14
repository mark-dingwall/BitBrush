package au.com.dingwall.mark.bitbrush.service;

import au.com.dingwall.mark.bitbrush.dto.UserCreateRequest;
import au.com.dingwall.mark.bitbrush.dto.UserIdentityResponse;
import au.com.dingwall.mark.bitbrush.dto.UserReconnectRequest;
import au.com.dingwall.mark.bitbrush.dto.UserRecoveryRequest;
import au.com.dingwall.mark.bitbrush.exception.DuplicateIdentityException;
import au.com.dingwall.mark.bitbrush.exception.InvalidCredentialsException;
import au.com.dingwall.mark.bitbrush.exception.InvalidPinException;
import au.com.dingwall.mark.bitbrush.exception.PinCapacityException;
import au.com.dingwall.mark.bitbrush.exception.TurnstileException;
import au.com.dingwall.mark.bitbrush.exception.UserNotFoundException;
import au.com.dingwall.mark.bitbrush.model.User;
import au.com.dingwall.mark.bitbrush.repository.UserRepository;
import au.com.dingwall.mark.bitbrush.validation.CanonicalUuidValidator;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.security.MessageDigest;

/** Owns identity workflows; verification is granted only after successful persistence or credentials. */
@Service
public class UserIdentityService {
    private final UserRepository users;
    private final PinCredentialService credentials;
    private final RecoveryAttemptService attempts;
    private final TurnstileService turnstile;
    private final AuthorIdGenerator authorIds;
    private final TransactionTemplate transaction;

    public UserIdentityService(UserRepository users, PinCredentialService credentials,
            RecoveryAttemptService attempts, TurnstileService turnstile,
            AuthorIdGenerator authorIds, TransactionTemplate transaction) {
        this.users = users;
        this.credentials = credentials;
        this.attempts = attempts;
        this.turnstile = turnstile;
        this.authorIds = authorIds;
        this.transaction = transaction;
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public UserIdentityResponse create(UserCreateRequest request, String turnstileToken) {
        requireCanonicalUuid(request.uuid());
        if ("You".equalsIgnoreCase(request.username())) {
            throw new IllegalArgumentException("Username is reserved");
        }
        var pin = credentials.canonicalize(request.pin());
        var confirmation = credentials.canonicalize(request.pinConfirmation());
        if (!MessageDigest.isEqual(pin.utf8(), confirmation.utf8())) {
            throw new InvalidPinException();
        }
        verifyTurnstile(turnstileToken);
        if (users.existsByUsername(request.username()) || users.existsById(request.uuid())
                || users.existsByAuthorId(request.uuid())) {
            throw new DuplicateIdentityException();
        }
        String hash = credentials.hash(pin);
        for (int attempt = 0; attempt < 5; attempt++) {
            User user = new User();
            user.setUuid(request.uuid());
            user.setUsername(request.username());
            user.setAuthorId(authorIds.generate());
            user.setPinHash(hash);
            user.setPinBackfilled(false);
            UserIdentityResponse response;
            try {
                response = transaction.execute(status -> {
                    users.saveAndFlush(user);
                    return identity(user);
                });
            } catch (RuntimeException failure) {
                String constraint = identityConstraint(failure);
                if ("uk_users_author_id".equals(constraint)) {
                    continue;
                }
                if ("pk_users_uuid".equals(constraint) || "uk_users_username".equals(constraint)) {
                    throw new DuplicateIdentityException();
                }
                throw failure;
            }
            turnstile.markVerified(user.getUuid());
            return response;
        }
        throw new IllegalStateException("Unable to allocate public author ID");
    }

    public UserIdentityResponse reconnect(UserReconnectRequest request) {
        requireCanonicalUuid(request.uuid());
        User user = users.findById(request.uuid()).orElseThrow(UserNotFoundException::new);
        turnstile.markVerified(user.getUuid());
        return identity(user);
    }

    public UserIdentityResponse recover(UserRecoveryRequest request, String turnstileToken, InetAddress sourceIp) {
        var pin = credentials.canonicalize(request.pin());
        attempts.recordIpAttempt(sourceIp);
        verifyTurnstile(turnstileToken);
        var reservation = attempts.recordAccountAttempt(request.username());
        var user = users.findByUsername(request.username());
        boolean verified = false;
        try {
            if (user.isPresent()) {
                verified = credentials.verify(pin, user.get().getPinHash());
            } else {
                credentials.verifyDummy(pin);
            }
        } catch (PinCapacityException capacity) {
            attempts.cancelAccountAttempt(reservation);
            throw capacity;
        }
        if (!verified) {
            throw new InvalidCredentialsException();
        }
        User recovered = user.orElseThrow(InvalidCredentialsException::new);
        attempts.clearAccount(request.username());
        turnstile.markVerified(recovered.getUuid());
        return identity(recovered);
    }

    private void verifyTurnstile(String token) {
        if (!turnstile.verify(token)) {
            throw new TurnstileException("Turnstile verification failed");
        }
    }

    private static void requireCanonicalUuid(String value) {
        if (!CanonicalUuidValidator.isCanonical(value)) {
            throw new IllegalArgumentException("Invalid identity request");
        }
    }

    private static UserIdentityResponse identity(User user) {
        return new UserIdentityResponse(user.getUuid(), user.getUsername());
    }

    private static String identityConstraint(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                String name = violation.getConstraintName();
                if ("pk_users_uuid".equals(name) || "uk_users_username".equals(name)
                        || "uk_users_author_id".equals(name)) {
                    return name;
                }
            }
        }
        return null;
    }
}
