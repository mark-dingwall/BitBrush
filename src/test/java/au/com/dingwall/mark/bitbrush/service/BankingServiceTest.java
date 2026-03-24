package au.com.dingwall.mark.bitbrush.service;

import au.com.dingwall.mark.bitbrush.config.BitbrushProperties;
import au.com.dingwall.mark.bitbrush.dto.BankStateResponse;
import au.com.dingwall.mark.bitbrush.exception.InsufficientBalanceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

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

    private BankingService bankingService;

    @BeforeEach
    void setUp() {
        when(bitbrushProperties.placement()).thenReturn(placement);
        when(placement.earnRateSeconds()).thenReturn(3);
        when(placement.maxBanked()).thenReturn(25);
        when(placement.startingBalance()).thenReturn(5);
        bankingService = new BankingService(messagingTemplate, bitbrushProperties);
    }

    @Test
    void earnTaskIncrementsConnectedUserBalance() {
        // Given: user UUID registered and connected (onUserConnect called)
        bankingService.onUserConnect("uuid-1", "session-1");
        // When: earnPoints() is called
        bankingService.earnPoints();
        // Then: balance increases by 1 (from starting balance to starting+1)
        BankStateResponse state = bankingService.getInitialState("uuid-1");
        assertEquals(6, state.balance());
    }

    @Test
    void earnTaskSkipsDisconnectedUser() {
        // Given: user UUID registered but disconnected (onUserDisconnect called)
        bankingService.onUserConnect("uuid-1", "session-1");
        bankingService.onUserDisconnect("uuid-1");
        // When: earnPoints() is called
        bankingService.earnPoints();
        // Then: balance does NOT change (freeze-on-disconnect)
        // Reconnect to inspect state (same uuid, same session)
        bankingService.onUserConnect("uuid-1", "session-1");
        BankStateResponse state = bankingService.getInitialState("uuid-1");
        assertEquals(5, state.balance());
    }

    @Test
    void earnTaskCapsAtMaxBanked() {
        // Given: user connected with balance already at maxBanked (25)
        bankingService.onUserConnect("uuid-1", "session-1");
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
        bankingService.onUserConnect("uuid-1", "session-1");
        // When: deductPoint(userUuid) is called
        // Then: no exception thrown, balance decreases by 1
        assertDoesNotThrow(() -> bankingService.deductPoint("uuid-1"));
        assertEquals(4, bankingService.getInitialState("uuid-1").balance());
    }

    @Test
    void deductPointThrowsAtZeroBalance() {
        // Given: user connected with balance = 0 (spend all 5 starting points)
        bankingService.onUserConnect("uuid-1", "session-1");
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
        bankingService.onUserConnect("uuid-1", "session-1");
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
        bankingService.onUserConnect("uuid-1", "session-1");
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
    void onUserConnectInitializesStartingBalance() {
        // Given: a new user UUID (not previously seen)
        // When: onUserConnect(uuid, sessionId) is called
        bankingService.onUserConnect("uuid-new", "session-new");
        // Then: getInitialState(sessionId).balance() == 5 (STARTING_BALANCE)
        assertEquals(5, bankingService.getInitialState("uuid-new").balance());
    }

    @Test
    void deductPoints_deductsExactAmount() {
        // Given: user connected with starting balance 5
        bankingService.onUserConnect("uuid-dp-1", "session-dp-1");
        // When: deductPoints(uuid, 3) is called
        int deducted = bankingService.deductPoints("uuid-dp-1", 3);
        // Then: returns 3, balance is 2
        assertEquals(3, deducted);
        assertEquals(2, bankingService.getInitialState("uuid-dp-1").balance());
    }

    @Test
    void deductPoints_partialDeduction_whenInsufficientBalance() {
        // Given: user connected with balance 2 (5 starting - 3 deducted)
        bankingService.onUserConnect("uuid-dp-2", "session-dp-2");
        bankingService.deductPoints("uuid-dp-2", 3);
        assertEquals(2, bankingService.getInitialState("uuid-dp-2").balance());
        // When: deductPoints(uuid, 5) is called — only 2 available
        int deducted = bankingService.deductPoints("uuid-dp-2", 5);
        // Then: returns 2 (partial), not 5
        assertEquals(2, deducted);
        assertEquals(0, bankingService.getInitialState("uuid-dp-2").balance());
    }

    @Test
    void deductPoints_returnsZero_whenNoBalance() {
        // Given: user connected with balance = 0
        bankingService.onUserConnect("uuid-dp-3", "session-dp-3");
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
