package au.com.dingwall.mark.bitbrush.service;

import au.com.dingwall.mark.bitbrush.config.PinProperties;
import au.com.dingwall.mark.bitbrush.dto.UserCreateRequest;
import au.com.dingwall.mark.bitbrush.dto.UserIdentityResponse;
import au.com.dingwall.mark.bitbrush.exception.DuplicateIdentityException;
import au.com.dingwall.mark.bitbrush.model.User;
import au.com.dingwall.mark.bitbrush.repository.UserRepository;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.exception.spi.ViolatedConstraintNameExtractor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false, properties = {
    "spring.jpa.properties.hibernate.dialect=au.com.dingwall.mark.bitbrush.service.UserIdentityServiceRaceTest$PrimaryKeyRaceDialect"
})
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserIdentityServiceRaceTest {
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager manager;

    /**
     * H2 exposes index/table text rather than PostgreSQL's structured constraint name.
     * This isolated scenario duplicates only the UUID; names and public IDs are distinct.
     * The test dialect adapts that real unique violation to the production PK contract.
     * No exception-message parsing or production classifier relaxation is involved.
     */
    public static class PrimaryKeyRaceDialect extends H2Dialect {
        @Override
        public ViolatedConstraintNameExtractor getViolatedConstraintNameExtractor() {
            return exception -> "23505".equals(exception.getSQLState()) ? "pk_users_uuid" : null;
        }
    }

    @AfterEach
    void cleanUp() {
        users.deleteAll();
    }

    @Test
    void stalePrecheckCannotOverwriteAnIdentityCommittedByAnotherRequest() throws Exception {
        String uuid = UUID.randomUUID().toString();
        var properties = new PinProperties("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            32, 1, 1, 16, 2, 1, Duration.ofMinutes(15), 5, 20, 10, 10);
        PinCredentialService credentials = spy(new PinCredentialService(properties));
        CountDownLatch requestBPrechecked = new CountDownLatch(1);
        CountDownLatch releaseRequestB = new CountDownLatch(1);
        doAnswer(invocation -> {
            PinCredentialCodec.CanonicalPin pin = invocation.getArgument(0);
            if (pin.value().equals("abcd")) {
                requestBPrechecked.countDown();
                if (!releaseRequestB.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Race gate timed out");
            }
            return invocation.callRealMethod();
        }).when(credentials).hash(any());

        TurnstileService turnstile = mock(TurnstileService.class);
        when(turnstile.verify("token")).thenReturn(true);
        UserRepository observedUsers = mock(UserRepository.class, org.mockito.AdditionalAnswers.delegatesTo(users));
        AtomicInteger newInserts = new AtomicInteger();
        doAnswer(invocation -> {
            User user = invocation.getArgument(0);
            if (user.isNew()) newInserts.incrementAndGet();
            return users.saveAndFlush(user);
        }).when(observedUsers).saveAndFlush(any());
        UserIdentityService service = new UserIdentityService(observedUsers, credentials,
            mock(RecoveryAttemptService.class), turnstile, new AuthorIdGenerator(new SecureRandom()),
            new TransactionTemplate(manager));

        try (var executor = Executors.newSingleThreadExecutor()) {
            var requestB = executor.submit(() -> {
                try {
                    service.create(new UserCreateRequest(uuid, "RequestB", "abcd", "abcd"), "token");
                    return (RuntimeException) null;
                } catch (RuntimeException failure) {
                    return failure;
                }
            });
            try {
                assertTrue(requestBPrechecked.await(5, TimeUnit.SECONDS), "Request B did not reach its precheck gate");
                UserIdentityResponse responseA = service.create(new UserCreateRequest(uuid, "RequestA", "1234", "1234"), "token");
                assertNotNull(responseA);
                User committedA = users.findById(uuid).orElseThrow();
                String originalAuthor = committedA.getAuthorId();
                String originalHash = committedA.getPinHash();

                releaseRequestB.countDown();
                RuntimeException failureB = requestB.get(5, TimeUnit.SECONDS);
                assertTrue(failureB instanceof DuplicateIdentityException, "The stale insert did not return an identity conflict");
                User reloaded = users.findById(uuid).orElseThrow();
                assertEquals("RequestA", reloaded.getUsername());
                assertEquals(originalAuthor, reloaded.getAuthorId());
                assertTrue(originalHash.equals(reloaded.getPinHash()), "The committed credential was overwritten");
                assertEquals(2, newInserts.get(), "Each request must attempt a new identity insert");
                assertEquals(1, users.count());
                verify(turnstile).markVerified(uuid);
            } finally {
                releaseRequestB.countDown();
            }
        }
    }
}
