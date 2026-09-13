package au.com.dingwall.mark.bitbrush.service;

import au.com.dingwall.mark.bitbrush.config.BitbrushProperties;
import au.com.dingwall.mark.bitbrush.dto.BankStateResponse;
import au.com.dingwall.mark.bitbrush.exception.InsufficientBalanceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import java.util.Set;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * Unit tests for BankingService (BANK-01 through BANK-05).
 *
 * Pure unit tests using Mockito — no @SpringBootTest required.
 * Mocks SimpMessagingTemplate and BitbrushProperties to isolate BankingService logic.
 */
@ExtendWith(MockitoExtension.class)
class BankingServiceTest {

    @Mock
    SimpMessagingTemplate messagingTemplate;

    @Mock
    BitbrushProperties bitbrushProperties;

    @Mock
    BitbrushProperties.Placement placement;

    @Mock SimpUserRegistry registry;
    @InjectMocks private BankingService bankingService;

    @BeforeEach
    void setUp() {
        lenient().when(bitbrushProperties.placement()).thenReturn(placement);
        lenient().when(placement.earnRateSeconds()).thenReturn(3);
        lenient().when(placement.maxBanked()).thenReturn(25);
        lenient().when(placement.startingBalance()).thenReturn(5);
        lenient().when(registry.getUsers()).thenReturn(Set.of());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void registryPresenceAloneControlsEarningAndOneFanoutPerPrincipal(int sessionCount) {
        bankingService.getInitialState("registry-user");
        SimpUser user = userWithSessions("registry-user", sessionCount);
        when(registry.getUsers()).thenReturn(Set.of(user));
        bankingService.earnPoints();
        assertEquals(6, bankingService.getInitialState("registry-user").balance());
        verify(messagingTemplate).convertAndSendToUser(eq("registry-user"), eq("/queue/bank"), any(BankStateResponse.class));
        verifyNoMoreInteractions(messagingTemplate);
        verify(registry).getUsers();

        when(registry.getUsers()).thenReturn(Set.of());
        bankingService.earnPoints();
        assertEquals(6, bankingService.getInitialState("registry-user").balance());
        verifyNoMoreInteractions(messagingTemplate);
        bankingService.deductPoint("registry-user");
        assertEquals(5, bankingService.getInitialState("registry-user").balance());
        bankingService.ensureBank("registry-user");
        assertEquals(5, bankingService.getInitialState("registry-user").balance());
        when(registry.getUsers()).thenReturn(Set.of(user));
        bankingService.earnPoints();
        assertEquals(6, bankingService.getInitialState("registry-user").balance());
    }

    @Test
    void oneTickAdvancesEachDistinctPrincipalOnce() {
        bankingService.ensureBank("one");
        bankingService.ensureBank("two");
        Set<SimpUser> connectedUsers = Set.of(userWithSessions("one", 2), userWithSessions("two", 1));
        when(registry.getUsers()).thenReturn(connectedUsers);
        bankingService.earnPoints();
        assertEquals(6, bankingService.getInitialState("one").balance());
        assertEquals(6, bankingService.getInitialState("two").balance());
        verify(registry).getUsers();
        verify(messagingTemplate).convertAndSendToUser(eq("one"), eq("/queue/bank"), any(BankStateResponse.class));
        verify(messagingTemplate).convertAndSendToUser(eq("two"), eq("/queue/bank"), any(BankStateResponse.class));
        verifyNoMoreInteractions(messagingTemplate);
    }

    private SimpUser userWithSessions(String name, int count) {
        SimpUser user = mock(SimpUser.class);
        when(user.getName()).thenReturn(name);
        java.util.Set<SimpSession> sessions = new java.util.HashSet<>();
        for (int index = 0; index < count; index++) {
            SimpSession session = mock(SimpSession.class);
            String id = name + "-session-" + index;
            lenient().when(session.getId()).thenReturn(id);
            lenient().when(session.getUser()).thenReturn(user);
            lenient().when(session.getSubscriptions()).thenReturn(Set.of());
            lenient().when(user.getSession(id)).thenReturn(session);
            sessions.add(session);
        }
        lenient().when(user.getPrincipal()).thenReturn(() -> name);
        lenient().when(user.getSessions()).thenReturn(Set.copyOf(sessions));
        lenient().when(user.hasSessions()).thenReturn(count > 0);
        return user;
    }

    @Test
    void initialStateCreatesASpendableBankDefensively() {
        assertEquals(5, bankingService.getInitialState("new-subscriber").balance());
        assertEquals(2, bankingService.deductPoints("new-subscriber", 2));
        assertEquals(3, bankingService.getInitialState("new-subscriber").balance());
    }

    private void connected(String name) {
        SimpUser user = mock(SimpUser.class);
        when(user.getName()).thenReturn(name);
        when(registry.getUsers()).thenReturn(Set.of(user));
    }

    @Test
    void earnTaskIncrementsConnectedUserBalance() {
        // Bank initialized and principal present in the registry.
        bankingService.ensureBank("uuid-1");
        connected("uuid-1");
        // When: earnPoints() is called
        bankingService.earnPoints();
        // Then: balance increases by 1 (from starting balance to starting+1)
        BankStateResponse state = bankingService.getInitialState("uuid-1");
        assertEquals(6, state.balance());
    }

    @Test
    void earnTaskSkipsDisconnectedUser() {
        // A retained bank without a registry user must not earn.
        bankingService.ensureBank("uuid-1");
        // When: earnPoints() is called
        bankingService.earnPoints();
        // Then: balance does NOT change (freeze-on-disconnect)
        // Initialization is idempotent and preserves the balance.
        bankingService.ensureBank("uuid-1");
        BankStateResponse state = bankingService.getInitialState("uuid-1");
        assertEquals(5, state.balance());
    }

    @Test
    void earnTaskCapsAtMaxBanked() {
        // Given: user connected with balance already at maxBanked (25)
        bankingService.ensureBank("uuid-1");
        connected("uuid-1");
        // Call earnPoints() 20 times (5 starting + 20 = 25 = maxBanked)
        for (int i = 0; i < 20; i++) {
            bankingService.earnPoints();
        }
        assertEquals(25, bankingService.getInitialState("uuid-1").balance());
        // When: earnPoints() is called one more time
        bankingService.earnPoints();
        // Then: balance remains at maxBanked (no overflow)
        assertEquals(25, bankingService.getInitialState("uuid-1").balance());
    }

    @Test
    void deductPointSucceedsWithBalance() {
        // Given: user connected with balance > 0
        bankingService.ensureBank("uuid-1");
        // When: deductPoint(userUuid) is called
        // Then: no exception thrown, balance decreases by 1
        assertDoesNotThrow(() -> bankingService.deductPoint("uuid-1"));
        assertEquals(4, bankingService.getInitialState("uuid-1").balance());
    }

    @Test
    void deductPointThrowsAtZeroBalance() {
        // Given: user connected with balance = 0 (spend all 5 starting points)
        bankingService.ensureBank("uuid-1");
        for (int i = 0; i < 5; i++) {
            bankingService.deductPoint("uuid-1");
        }
        // When: deductPoint(userUuid) is called with balance = 0
        // Then: InsufficientBalanceException is thrown
        assertThrows(InsufficientBalanceException.class, () -> bankingService.deductPoint("uuid-1"));
    }

    @Test
    void initialStateReturnsCorrectShape() {
        // Given: user connected with a known balance
        bankingService.ensureBank("uuid-1");
        // When: getInitialState(sessionId) is called
        BankStateResponse r = bankingService.getInitialState("uuid-1");
        // Then: returns BankStateResponse with correct fields
        assertEquals(5, r.balance());
        assertEquals(25, r.maxBalance());
        assertTrue(r.secondsUntilNextPoint() >= 0);
    }

    @Test
    void concurrentDeductIsAtomic() throws InterruptedException {
        // Given: user connected with balance = 1
        bankingService.ensureBank("uuid-1");
        // Spend 4 of the 5 starting points to get to balance=1
        for (int i = 0; i < 4; i++) {
            bankingService.deductPoint("uuid-1");
        }
        assertEquals(1, bankingService.getInitialState("uuid-1").balance());

        // When: 2 threads simultaneously call deductPoint(userUuid)
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    bankingService.deductPoint("uuid-1");
                    successes.incrementAndGet();
                } catch (InsufficientBalanceException e) {
                    failures.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // release both threads simultaneously
        doneLatch.await();
        executor.shutdown();

        // Then: exactly 1 succeeds, exactly 1 throws; balance never goes negative
        assertEquals(1, successes.get(), "Exactly 1 thread should succeed");
        assertEquals(1, failures.get(), "Exactly 1 thread should throw InsufficientBalanceException");
        assertEquals(0, bankingService.getInitialState("uuid-1").balance());
    }

    @Test
    void ensureBankInitializesStartingBalance() {
        // Given: a new user UUID (not previously seen)
        // When: the connected principal's bank is initialized
        bankingService.ensureBank("uuid-new");
        // Then: getInitialState(sessionId).balance() == 5 (STARTING_BALANCE)
        assertEquals(5, bankingService.getInitialState("uuid-new").balance());
    }

    @Test
    void deductPoints_deductsExactAmount() {
        // Given: user connected with starting balance 5
        bankingService.ensureBank("uuid-dp-1");
        // When: deductPoints(uuid, 3) is called
        int deducted = bankingService.deductPoints("uuid-dp-1", 3);
        // Then: returns 3, balance is 2
        assertEquals(3, deducted);
        assertEquals(2, bankingService.getInitialState("uuid-dp-1").balance());
    }

    @Test
    void deductPoints_partialDeduction_whenInsufficientBalance() {
        // Given: user connected with balance 2 (5 starting - 3 deducted)
        bankingService.ensureBank("uuid-dp-2");
        bankingService.deductPoints("uuid-dp-2", 3);
        assertEquals(2, bankingService.getInitialState("uuid-dp-2").balance());
        // When: deductPoints(uuid, 5) is called — only 2 available
        int deducted = bankingService.deductPoints("uuid-dp-2", 5);
        // Then: returns 2 (partial), not 5
        assertEquals(2, deducted);
        assertEquals(0, bankingService.getInitialState("uuid-dp-2").balance());
    }

    @Test
    void getInitialState_nullUuid_returnsStartingBalance() {
        BankStateResponse r = bankingService.getInitialState(null);
        assertEquals(5, r.balance());
        assertEquals(25, r.maxBalance());
    }

    @Test
    void getInitialState_unknownUuid_returnsStartingBalance() {
        BankStateResponse r = bankingService.getInitialState("uuid-never-connected");
        assertEquals(5, r.balance());
        assertEquals(25, r.maxBalance());
    }

    @Test
    void deductPoint_unknownUuid_throwsInsufficientBalance() {
        assertThrows(InsufficientBalanceException.class,
                () -> bankingService.deductPoint("uuid-never-connected"));
    }

    @Test
    void deductPoints_returnsZero_whenNoBalance() {
        // Given: user connected with balance = 0
        bankingService.ensureBank("uuid-dp-3");
        for (int i = 0; i < 5; i++) {
            bankingService.deductPoint("uuid-dp-3");
        }
        assertEquals(0, bankingService.getInitialState("uuid-dp-3").balance());
        // When: deductPoints(uuid, 1) is called
        int deducted = bankingService.deductPoints("uuid-dp-3", 1);
        // Then: returns 0
        assertEquals(0, deducted);
    }
}
