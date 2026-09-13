package au.com.dingwall.mark.bitbrush.service;

import au.com.dingwall.mark.bitbrush.dto.*;
import au.com.dingwall.mark.bitbrush.exception.*;
import au.com.dingwall.mark.bitbrush.model.User;
import au.com.dingwall.mark.bitbrush.repository.UserRepository;
import au.com.dingwall.mark.bitbrush.service.PinCredentialCodec.CanonicalPin;
import au.com.dingwall.mark.bitbrush.service.RecoveryAttemptService.AccountAttemptReservation;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserIdentityServiceTest {
    private static final String UUID = "681596ed-5ac6-44a4-a340-a390d2f9456c";
    private static final String AUTHOR = "author_12345678901234567890123456789012";
    private static final String RAW_PIN = "A😀b!";
    private static final String HASH = "test-encoded-hash";
    private static final CanonicalPin PIN = new CanonicalPin(RAW_PIN, RAW_PIN.getBytes(StandardCharsets.UTF_8));
    private static final InetAddress IP = InetAddress.getLoopbackAddress();

    @Mock UserRepository users;
    @Mock PinCredentialService credentials;
    @Mock RecoveryAttemptService attempts;
    @Mock TurnstileService turnstile;
    @Mock AuthorIdGenerator authorIds;
    @Mock PlatformTransactionManager transactionManager;
    TransactionTemplate transaction;
    UserIdentityService service;

    @BeforeEach
    void setUp() {
        transaction = spy(new TransactionTemplate(transactionManager));
        service = new UserIdentityService(users, credentials, attempts, turnstile, authorIds, transaction);
    }

    @Test
    void creationCommitsTheCompleteIdentityBeforeGrantingVerification() {
        createReady();
        UserIdentityResponse response = service.create(createRequest(), "token");
        assertNotNull(response);
        assertTrue(UUID.equals(response.uuid()), "Identity response mismatch");
        assertEquals("Artist", response.username());
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        var order = inOrder(credentials, turnstile, users, transactionManager);
        order.verify(credentials, times(2)).canonicalize(RAW_PIN);
        order.verify(turnstile).verify("token");
        order.verify(users).existsByUsername("Artist");
        order.verify(users).existsById(UUID);
        order.verify(users).existsByAuthorId(UUID);
        order.verify(credentials).hash(PIN);
        order.verify(users).saveAndFlush(saved.capture());
        order.verify(transactionManager).commit(any());
        order.verify(turnstile).markVerified(UUID);
        User identity = saved.getValue();
        assertTrue(identity.isNew(), "New identity must use insert-only persistence");
        assertTrue(UUID.equals(identity.getUuid()), "Saved identity mismatch");
        assertEquals("Artist", identity.getUsername());
        assertEquals(AUTHOR, identity.getAuthorId());
        assertTrue(HASH.equals(identity.getPinHash()), "Credential was not persisted");
        assertFalse(identity.isPinBackfilled());
    }

    @Test
    void mismatchingCanonicalPinsStopBeforeExternalWork() {
        when(credentials.canonicalize(RAW_PIN)).thenReturn(PIN);
        when(credentials.canonicalize("xxxx")).thenReturn(new CanonicalPin("xxxx", new byte[] {120,120,120,120}));
        assertThrows(InvalidPinException.class, () -> service.create(
            new UserCreateRequest(UUID, "Artist", RAW_PIN, "xxxx"), "token"));
        verifyNoInteractions(turnstile, users, authorIds, transactionManager);
    }

    @Test
    void malformedPinStopsBeforeTurnstileAndPersistence() {
        when(credentials.canonicalize("bad")).thenThrow(new InvalidPinException());
        assertThrows(InvalidPinException.class, () -> service.create(
            new UserCreateRequest(UUID, "Artist", "bad", RAW_PIN), "token"));
        verifyNoInteractions(turnstile, users, transactionManager);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not-a-uuid", "1-1-1-1-1", "681596ED-5AC6-44A4-A340-A390D2F9456C", "author_12345678901234567890123456789012"})
    void invalidPrivateIdsCannotCreateOrReconnect(String uuid) {
        assertThrows(IllegalArgumentException.class, () -> service.create(new UserCreateRequest(uuid, "Artist", RAW_PIN, RAW_PIN), "token"));
        assertThrows(IllegalArgumentException.class, () -> service.reconnect(new UserReconnectRequest(uuid)));
        verifyNoInteractions(users, credentials, turnstile, transactionManager);
    }

    @ParameterizedTest
    @ValueSource(strings = {"You", "you", "YOU"})
    void reservedUsernameCannotCreate(String username) {
        assertThrows(IllegalArgumentException.class, () -> service.create(new UserCreateRequest(UUID, username, RAW_PIN, RAW_PIN), "token"));
        verifyNoInteractions(users, turnstile, transactionManager);
    }

    @Test
    void rejectedTurnstileCannotProbeUniquenessOrHash() {
        when(credentials.canonicalize(RAW_PIN)).thenReturn(PIN);
        assertThrows(TurnstileException.class, () -> service.create(createRequest(), "bad-token"));
        verifyNoInteractions(users, transactionManager);
        verify(credentials, never()).hash(any());
        verify(turnstile, never()).markVerified(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"username", "uuid", "legacy-author"})
    void uniquenessPrechecksPreventCredentialWork(String conflict) {
        when(credentials.canonicalize(RAW_PIN)).thenReturn(PIN);
        when(turnstile.verify("token")).thenReturn(true);
        switch (conflict) {
            case "username" -> when(users.existsByUsername("Artist")).thenReturn(true);
            case "uuid" -> when(users.existsById(UUID)).thenReturn(true);
            default -> when(users.existsByAuthorId(UUID)).thenReturn(true);
        }
        assertThrows(DuplicateIdentityException.class, () -> service.create(createRequest(), "token"));
        verify(credentials, never()).hash(any());
        verify(users, never()).saveAndFlush(any());
        verify(turnstile, never()).markVerified(any());
    }

    @Test
    void authorIdCollisionRetriesInANewTransaction() {
        createReady();
        when(authorIds.generate()).thenReturn("author_collision", AUTHOR);
        when(users.saveAndFlush(any())).thenThrow(integrity("uk_users_author_id")).thenAnswer(invocation -> invocation.getArgument(0));
        UserIdentityResponse response = service.create(createRequest(), "token");
        assertNotNull(response);
        verify(transaction, times(2)).execute(any());
        verify(transactionManager).rollback(any());
        verify(transactionManager).commit(any());
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users, times(2)).saveAndFlush(saved.capture());
        assertEquals("author_collision", saved.getAllValues().get(0).getAuthorId());
        assertEquals(AUTHOR, saved.getAllValues().get(1).getAuthorId());
        assertNotSame(saved.getAllValues().get(0), saved.getAllValues().get(1));
        verify(turnstile).markVerified(UUID);
        verify(credentials).hash(PIN);
    }

    @Test
    void fivePublicIdCollisionsStopWithoutMarkingAnIdentity() {
        createReady();
        when(users.saveAndFlush(any())).thenThrow(integrity("uk_users_author_id"));
        assertThrows(IllegalStateException.class, () -> service.create(createRequest(), "token"));
        verify(transaction, times(5)).execute(any());
        verify(transactionManager, times(5)).rollback(any());
        verify(transactionManager, never()).commit(any());
        verify(turnstile, never()).markVerified(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"pk_users_uuid", "uk_users_username"})
    void namedIdentityConstraintRacesMapToConflict(String constraint) {
        createReady();
        when(users.saveAndFlush(any())).thenThrow(integrity(constraint));
        assertThrows(DuplicateIdentityException.class, () -> service.create(createRequest(), "token"));
        verify(transaction).execute(any());
        verify(turnstile, never()).markVerified(any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"unrelated_constraint", "UK_USERS_AUTHOR_ID"})
    void unrelatedStructuredIntegrityErrorsPropagate(String constraint) {
        createReady();
        DataIntegrityViolationException failure = integrity(constraint);
        when(users.saveAndFlush(any())).thenThrow(failure);
        assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> service.create(createRequest(), "token")));
        verify(transaction).execute(any());
        verify(turnstile, never()).markVerified(any());
    }

    @Test
    void sqlStateAndMessageCannotStandInForAStructuredConstraintName() {
        createReady();
        DataIntegrityViolationException failure = new DataIntegrityViolationException(
            "uk_users_author_id", new SQLException("pk_users_uuid", "23505"));
        when(users.saveAndFlush(any())).thenThrow(failure);
        assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> service.create(createRequest(), "token")));
        verify(transaction).execute(any());
        verify(turnstile, never()).markVerified(any());
    }

    @Test
    void flushFailureCannotGrantVerification() {
        createReady();
        IllegalStateException failure = new IllegalStateException("Flush failed");
        when(users.saveAndFlush(any())).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.create(createRequest(), "token")));
        verify(transactionManager).rollback(any());
        verify(turnstile, never()).markVerified(any());
    }

    @Test
    void commitFailureCannotGrantVerification() {
        createReady();
        doThrow(new IllegalStateException("Commit failed")).when(transactionManager).commit(any());
        assertThrows(IllegalStateException.class, () -> service.create(createRequest(), "token"));
        verify(users).saveAndFlush(any());
        verify(turnstile, never()).markVerified(any());
    }

    @Test
    void hashingCapacityFailureCannotInsertOrMarkVerified() {
        when(credentials.canonicalize(RAW_PIN)).thenReturn(PIN);
        when(turnstile.verify("token")).thenReturn(true);
        when(credentials.hash(PIN)).thenThrow(new PinCapacityException(1));
        assertThrows(PinCapacityException.class, () -> service.create(createRequest(), "token"));
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(transactionManager);
        verify(turnstile, never()).markVerified(any());
    }

    @Test
    void reconnectReturnsAuthoritativeUsernameWithoutMutation() {
        when(users.findById(UUID)).thenReturn(Optional.of(user()));
        UserIdentityResponse response = service.reconnect(new UserReconnectRequest(UUID));
        assertNotNull(response);
        assertTrue(UUID.equals(response.uuid()), "Identity response mismatch");
        assertEquals("Artist", response.username());
        verify(users, never()).save(any());
        verify(users, never()).saveAndFlush(any());
        verify(turnstile).markVerified(UUID);
        verify(turnstile, never()).verify(any());
        verifyNoInteractions(credentials, attempts, transactionManager);
    }

    @Test
    void unknownReconnectCannotGrantVerification() {
        assertThrows(UserNotFoundException.class, () -> service.reconnect(new UserReconnectRequest(UUID)));
        verifyNoInteractions(turnstile, credentials, transactionManager);
    }

    @Test
    void recoveryOrdersAdmissionAndVerificationBeforeGrantingAccess() {
        AccountAttemptReservation reservation = recoveryReady();
        when(credentials.verify(PIN, HASH)).thenReturn(true);
        UserIdentityResponse response = service.recover(new UserRecoveryRequest("Artist", RAW_PIN), "token", IP);
        assertNotNull(response);
        assertTrue(UUID.equals(response.uuid()), "Identity response mismatch");
        assertEquals("Artist", response.username());
        var order = inOrder(credentials, attempts, turnstile, users);
        order.verify(credentials).canonicalize(RAW_PIN);
        order.verify(attempts).recordIpAttempt(IP);
        order.verify(turnstile).verify("token");
        order.verify(attempts).recordAccountAttempt("Artist");
        order.verify(users).findByUsername("Artist");
        order.verify(credentials).verify(PIN, HASH);
        order.verify(attempts).clearAccount("Artist");
        order.verify(turnstile).markVerified(UUID);
        verify(attempts, never()).cancelAccountAttempt(reservation);
        verifyNoInteractions(transactionManager, authorIds);
    }

    @Test
    void wrongPinRetainsTheAccountAttemptAndDoesNotGrantVerification() {
        recoveryReady();
        assertThrows(InvalidCredentialsException.class, () -> service.recover(new UserRecoveryRequest("Artist", RAW_PIN), "token", IP));
        verify(credentials).verify(PIN, HASH);
        verify(attempts, never()).clearAccount(any());
        verify(attempts, never()).cancelAccountAttempt(any());
        verify(turnstile, never()).markVerified(any());
    }

    @Test
    void unknownUsernamePerformsDummyWorkAndRetainsTheEquivalentAccountAttempt() {
        when(credentials.canonicalize(RAW_PIN)).thenReturn(PIN);
        when(turnstile.verify("token")).thenReturn(true);
        when(attempts.recordAccountAttempt("artist")).thenReturn(new AccountAttemptReservation("artist", 9));
        InvalidCredentialsException failure = assertThrows(InvalidCredentialsException.class,
            () -> service.recover(new UserRecoveryRequest("artist", RAW_PIN), "token", IP));
        assertEquals("Invalid username or PIN", failure.getMessage());
        var order = inOrder(attempts, turnstile, users, credentials);
        order.verify(credentials).canonicalize(RAW_PIN);
        order.verify(attempts).recordIpAttempt(IP);
        order.verify(turnstile).verify("token");
        order.verify(attempts).recordAccountAttempt("artist");
        order.verify(users).findByUsername("artist");
        order.verify(credentials).verifyDummy(PIN);
        verify(credentials, never()).verify(any(), any());
        verify(attempts, never()).clearAccount(any());
        verify(attempts, never()).cancelAccountAttempt(any());
        verify(turnstile, never()).markVerified(any());
    }

    @Test
    void malformedRecoveryPinDoesNotConsumeAnyAttempt() {
        when(credentials.canonicalize("bad")).thenThrow(new InvalidPinException());
        assertThrows(InvalidPinException.class, () -> service.recover(new UserRecoveryRequest("Artist", "bad"), "token", IP));
        verifyNoInteractions(attempts, turnstile, users);
    }

    @Test
    void ipRejectionStopsBeforeTurnstileOrAccountLookup() {
        when(credentials.canonicalize(RAW_PIN)).thenReturn(PIN);
        doThrow(new RecoveryThrottledException(900)).when(attempts).recordIpAttempt(IP);
        assertThrows(RecoveryThrottledException.class, () -> service.recover(new UserRecoveryRequest("Artist", RAW_PIN), "token", IP));
        verifyNoInteractions(turnstile, users);
        verify(attempts, never()).recordAccountAttempt(any());
    }

    @Test
    void rejectedRecoveryTurnstileConsumesOnlyTheIpAttempt() {
        when(credentials.canonicalize(RAW_PIN)).thenReturn(PIN);
        assertThrows(TurnstileException.class, () -> service.recover(new UserRecoveryRequest("Artist", RAW_PIN), "bad-token", IP));
        var order = inOrder(attempts, turnstile);
        order.verify(attempts).recordIpAttempt(IP);
        order.verify(turnstile).verify("bad-token");
        verify(attempts, never()).recordAccountAttempt(any());
        verifyNoInteractions(users);
    }

    @Test
    void accountRejectionStopsBeforeCredentialLookup() {
        when(credentials.canonicalize(RAW_PIN)).thenReturn(PIN);
        when(turnstile.verify("token")).thenReturn(true);
        when(attempts.recordAccountAttempt("Artist")).thenThrow(new RecoveryCapacityException(900));
        assertThrows(RecoveryCapacityException.class, () -> service.recover(new UserRecoveryRequest("Artist", RAW_PIN), "token", IP));
        verifyNoInteractions(users);
        verify(credentials, never()).verify(any(), any());
        verify(turnstile, never()).markVerified(any());
    }

    @Test
    void fiveCapacityFailuresReleaseTheirOwnReservationsAndSixthCanRecover() {
        recoveryReady();
        when(attempts.recordAccountAttempt("Artist")).thenAnswer(new org.mockito.stubbing.Answer<AccountAttemptReservation>() {
            long sequence;
            public AccountAttemptReservation answer(org.mockito.invocation.InvocationOnMock invocation) {
                return new AccountAttemptReservation("Artist", ++sequence);
            }
        });
        when(credentials.verify(PIN, HASH))
            .thenThrow(new PinCapacityException(1), new PinCapacityException(1), new PinCapacityException(1),
                new PinCapacityException(1), new PinCapacityException(1))
            .thenReturn(true);
        for (int i = 1; i <= 5; i++) {
            assertThrows(PinCapacityException.class, () -> service.recover(new UserRecoveryRequest("Artist", RAW_PIN), "token", IP));
            verify(attempts).cancelAccountAttempt(new AccountAttemptReservation("Artist", i));
        }
        UserIdentityResponse response = service.recover(new UserRecoveryRequest("Artist", RAW_PIN), "token", IP);
        assertNotNull(response);
        verify(attempts, times(6)).recordIpAttempt(IP);
        verify(attempts).clearAccount("Artist");
        verify(attempts, times(5)).cancelAccountAttempt(any());
        verify(turnstile).markVerified(UUID);
    }

    @Test
    void unknownAccountCapacityFailureCancelsOnlyItsOwnReservation() {
        when(credentials.canonicalize(RAW_PIN)).thenReturn(PIN);
        when(turnstile.verify("token")).thenReturn(true);
        AccountAttemptReservation own = new AccountAttemptReservation("unknown", 2);
        when(attempts.recordAccountAttempt("unknown")).thenReturn(own);
        doThrow(new PinCapacityException(1)).when(credentials).verifyDummy(PIN);
        assertThrows(PinCapacityException.class, () -> service.recover(new UserRecoveryRequest("unknown", RAW_PIN), "token", IP));
        verify(attempts).cancelAccountAttempt(own);
        verify(attempts, never()).clearAccount(any());
        verify(turnstile, never()).markVerified(any());
    }

    @Test
    void capacityCancellationPreservesAnotherPendingAccountAttemptAndIpHistory() {
        var properties = new au.com.dingwall.mark.bitbrush.config.PinProperties(
            "test-pepper", 32, 1, 1, 16, 1, 1, java.time.Duration.ofMinutes(15), 2, 2, 10, 10, "");
        RecoveryAttemptService realAttempts = new RecoveryAttemptService(properties, java.time.Clock.systemUTC());
        realAttempts.recordAccountAttempt("Artist"); // Another admitted request is still pending.
        realAttempts.recordIpAttempt(IP);
        when(credentials.canonicalize(RAW_PIN)).thenReturn(PIN);
        when(turnstile.verify("token")).thenReturn(true);
        when(users.findByUsername("Artist")).thenReturn(Optional.of(user()));
        when(credentials.verify(PIN, HASH)).thenThrow(new PinCapacityException(1));
        UserIdentityService workflow = new UserIdentityService(users, credentials, realAttempts, turnstile, authorIds, transaction);
        assertThrows(PinCapacityException.class,
            () -> workflow.recover(new UserRecoveryRequest("Artist", RAW_PIN), "token", IP));
        assertDoesNotThrow(() -> realAttempts.recordAccountAttempt("Artist"));
        assertThrows(RecoveryThrottledException.class, () -> realAttempts.recordAccountAttempt("Artist"));
        assertThrows(RecoveryThrottledException.class, () -> realAttempts.recordIpAttempt(IP));
        verify(turnstile, never()).markVerified(any());
    }

    private UserCreateRequest createRequest() { return new UserCreateRequest(UUID, "Artist", RAW_PIN, RAW_PIN); }

    private void createReady() {
        lenient().when(credentials.canonicalize(RAW_PIN)).thenReturn(PIN);
        lenient().when(turnstile.verify("token")).thenReturn(true);
        lenient().when(credentials.hash(PIN)).thenReturn(HASH);
        lenient().when(authorIds.generate()).thenReturn(AUTHOR);
        lenient().when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        lenient().when(users.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private AccountAttemptReservation recoveryReady() {
        lenient().when(credentials.canonicalize(RAW_PIN)).thenReturn(PIN);
        lenient().when(turnstile.verify("token")).thenReturn(true);
        AccountAttemptReservation reservation = new AccountAttemptReservation("Artist", 1);
        lenient().when(attempts.recordAccountAttempt("Artist")).thenReturn(reservation);
        lenient().when(users.findByUsername("Artist")).thenReturn(Optional.of(user()));
        return reservation;
    }

    static User user() {
        User user = new User();
        user.setUuid(UUID);
        user.setUsername("Artist");
        user.setAuthorId(AUTHOR);
        user.setPinHash(HASH);
        user.setPinBackfilled(false);
        return user;
    }

    private DataIntegrityViolationException integrity(String constraint) {
        return new DataIntegrityViolationException("Insert rejected",
            new IllegalStateException("Wrapped",
                new ConstraintViolationException("Insert rejected", new SQLException("Insert rejected", "23505"), constraint)));
    }
}
