package au.com.dingwall.mark.bitbrush.service;

import au.com.dingwall.mark.bitbrush.config.PinProperties;
import au.com.dingwall.mark.bitbrush.exception.RecoveryCapacityException;
import au.com.dingwall.mark.bitbrush.exception.RecoveryThrottledException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class RecoveryAttemptServiceTest {

    private static final Instant START = Instant.parse("2026-09-12T00:00:00Z");

    enum Dimension {
        ACCOUNT, IP;

        void record(RecoveryAttemptService service, int key) {
            if (this == ACCOUNT) {
                service.recordAccountAttempt("unknown-" + key);
            } else {
                service.recordIpAttempt(ip(key));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Dimension.class)
    void enforcesConfiguredLimitAndRollingExpiryAtTheExactBoundary(Dimension dimension) {
        MutableClock clock = new MutableClock();
        RecoveryAttemptService service = service(clock, 2, 2, 10, 10);
        dimension.record(service, 1);
        clock.atMillis(4_000);
        dimension.record(service, 1);
        clock.atMillis(9_001);
        assertThrottle(() -> dimension.record(service, 1), 1);
        dimension.record(service, 2);
        clock.atMillis(9_999);
        assertThrottle(() -> dimension.record(service, 1), 1);
        clock.atMillis(10_000);
        dimension.record(service, 1);
        assertThrottle(() -> dimension.record(service, 1), 4);
        clock.atMillis(14_001);
        dimension.record(service, 1);
        assertThrottle(() -> dimension.record(service, 1), 6);
    }

    @Test
    void appliesIndependentAccountAndIpLimitsAndClearsOnlyTheExactUsername() {
        RecoveryAttemptService service = service(new MutableClock(), 1, 2, 10, 10);
        service.recordAccountAttempt("Unknown");
        service.recordAccountAttempt("unknown");
        service.recordIpAttempt(ip(1));
        service.recordIpAttempt(ip(1));
        assertThrottle(() -> service.recordAccountAttempt("Unknown"), 10);
        assertThrottle(() -> service.recordIpAttempt(ip(1)), 10);
        service.clearAccount("Unknown");
        service.recordAccountAttempt("Unknown");
        assertThrottle(() -> service.recordAccountAttempt("unknown"), 10);
        assertThrottle(() -> service.recordIpAttempt(ip(1)), 10);
        service.clearAccount("missing");
    }

    @ParameterizedTest
    @EnumSource(Dimension.class)
    void capacityDelayWaitsForTheResidentKeysFinalTimestamp(Dimension dimension) {
        MutableClock clock = new MutableClock();
        RecoveryAttemptService service = service(clock, 3, 3, 1, 1);
        dimension.record(service, 1);
        clock.atMillis(4_000);
        dimension.record(service, 1);
        clock.atMillis(9_001);
        assertCapacity(() -> dimension.record(service, 2), 5);
        clock.atMillis(10_000);
        assertCapacity(() -> dimension.record(service, 2), 4);
        clock.atMillis(14_000);
        dimension.record(service, 2);
        assertCapacity(() -> dimension.record(service, 3), 10);
    }

    @ParameterizedTest
    @EnumSource(Dimension.class)
    void capacityUsesTheEarliestWholeKeyExpiryAndReusesExpiredSlots(Dimension dimension) {
        MutableClock clock = new MutableClock();
        RecoveryAttemptService service = service(clock, 3, 3, 2, 2);
        dimension.record(service, 1);
        clock.atMillis(2_000);
        dimension.record(service, 2);
        clock.atMillis(4_000);
        dimension.record(service, 1);
        assertCapacity(() -> dimension.record(service, 3), 8);
        clock.atMillis(12_000);
        dimension.record(service, 3);
        assertCapacity(() -> dimension.record(service, 4), 2);
    }

    @Test
    void cancellationRemovesOnlyItsUniqueReservationAndImmediatelyReleasesTheFinalSlot() {
        MutableClock clock = new MutableClock();
        RecoveryAttemptService service = service(clock, 2, 2, 1, 1);
        var first = service.recordAccountAttempt("name");
        var second = service.recordAccountAttempt("name");
        assertThat(first.username()).isEqualTo("name");
        assertThat(second.sequence()).isGreaterThan(first.sequence());
        service.cancelAccountAttempt(first);
        service.cancelAccountAttempt(first);
        var third = service.recordAccountAttempt("name");
        assertThrottle(() -> service.recordAccountAttempt("name"), 10);
        service.cancelAccountAttempt(second);
        assertCapacity(() -> service.recordAccountAttempt("other"), 10);
        service.cancelAccountAttempt(third);
        service.recordAccountAttempt("other");
    }

    @Test
    void staleCancellationCannotRemoveReservationsAfterClearOrExpiry() {
        MutableClock clock = new MutableClock();
        RecoveryAttemptService service = service(clock, 1, 1, 1, 1);
        var cleared = service.recordAccountAttempt("name");
        service.clearAccount("name");
        var expired = service.recordAccountAttempt("name");
        service.cancelAccountAttempt(cleared);
        assertThrottle(() -> service.recordAccountAttempt("name"), 10);
        clock.atMillis(10_000);
        service.cancelAccountAttempt(expired);
        var current = service.recordAccountAttempt("name");
        service.cancelAccountAttempt(expired);
        assertThat(current.sequence()).isGreaterThan(expired.sequence());
        assertThrottle(() -> service.recordAccountAttempt("name"), 10);
    }

    @Test
    void acceptsExactlyTwentyConcurrentIpAttemptsWithTheDefaultLimit() {
        var service = new RecoveryAttemptService(properties(Duration.ofMinutes(15), 5, 20, 10_000, 10_000), new MutableClock());
        InetAddress ip = ip(1);
        AtomicInteger accepted = new AtomicInteger();
        IntStream.range(0, 30).parallel().forEach(i -> {
            try {
                service.recordIpAttempt(ip);
                accepted.incrementAndGet();
            } catch (RecoveryThrottledException ignored) {
                // Expected after the twentieth accepted request.
            }
        });
        assertThat(accepted).hasValue(20);
        assertThrottle(() -> service.recordIpAttempt(ip), 900);
    }

    @Test
    void acceptsExactlyFiveConcurrentAccountAttemptsWithTheDefaultLimit() {
        var service = new RecoveryAttemptService(properties(Duration.ofMinutes(15), 5, 20, 10_000, 10_000), new MutableClock());
        AtomicInteger accepted = new AtomicInteger();
        IntStream.range(0, 30).parallel().forEach(i -> {
            try {
                service.recordAccountAttempt("unknown");
                accepted.incrementAndGet();
            } catch (RecoveryThrottledException ignored) {
                // Expected after the fifth accepted request.
            }
        });
        assertThat(accepted).hasValue(5);
        assertThrottle(() -> service.recordAccountAttempt("unknown"), 900);
    }

    @ParameterizedTest
    @EnumSource(Dimension.class)
    void simultaneousFirstKeysPublishExactlyOneCompleteWindowWithoutLeakingCapacity(Dimension dimension) throws Exception {
        MutableClock clock = new MutableClock();
        RecoveryAttemptService service = service(clock, 2, 2, 1, 1);
        CyclicBarrier start = new CyclicBarrier(12);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(12)) {
            var tasks = IntStream.range(0, 12).mapToObj(key -> executor.submit(() -> {
                start.await(3, TimeUnit.SECONDS);
                try {
                    dimension.record(service, key);
                    accepted.incrementAndGet();
                } catch (RecoveryCapacityException exception) {
                    assertThat(exception.retryAfterSeconds()).isEqualTo(10);
                    rejected.incrementAndGet();
                }
                return null;
            })).toList();
            for (var task : tasks) {
                task.get(5, TimeUnit.SECONDS);
            }
        }
        assertThat(accepted).hasValue(1);
        assertThat(rejected).hasValue(11);
        clock.atMillis(10_000);
        dimension.record(service, 99);
        assertCapacity(() -> dimension.record(service, 100), 10);
    }

    @ParameterizedTest
    @EnumSource(Dimension.class)
    void clockIsSampledOnceForAcceptedThrottledAndCapacityRejectedOperations(Dimension dimension) {
        MutableClock clock = new MutableClock();
        clock.advanceOnRead = Duration.ofSeconds(1);
        RecoveryAttemptService service = service(clock, 1, 1, 1, 1);
        dimension.record(service, 1);
        assertThat(clock.reads).hasValue(1);
        assertThrottle(() -> dimension.record(service, 1), 9);
        assertThat(clock.reads).hasValue(2);
        assertCapacity(() -> dimension.record(service, 2), 8);
        assertThat(clock.reads).hasValue(3);
    }

    @Test
    void cancellationSamplesOnceAndPrunesUsingThatInstant() {
        MutableClock clock = new MutableClock();
        RecoveryAttemptService service = service(clock, 2, 2, 1, 1);
        var first = service.recordAccountAttempt("name");
        clock.atMillis(1_000);
        var second = service.recordAccountAttempt("name");
        clock.atMillis(10_000);
        clock.advanceOnRead = Duration.ofSeconds(1);
        clock.reads.set(0);
        service.cancelAccountAttempt(first);
        assertThat(clock.reads).hasValue(1);
        clock.atMillis(10_000);
        assertCapacity(() -> service.recordAccountAttempt("other"), 1);
        clock.reads.set(0);
        service.cancelAccountAttempt(second);
        assertThat(clock.reads).hasValue(1);
        clock.reads.set(0);
        service.cancelAccountAttempt(second);
        assertThat(clock.reads).hasValue(1);
        service.recordAccountAttempt("other");
    }

    @ParameterizedTest
    @EnumSource(Dimension.class)
    void capacitySnapshotFinishesBeforeAnExistingKeyUpdate(Dimension dimension) throws Exception {
        BlockingClock clock = new BlockingClock();
        RecoveryAttemptService service = service(clock, 2, 2, 1, 1);
        dimension.record(service, 1);
        clock.atMillis(4_000);
        clock.arm();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var snapshot = executor.submit(() -> catchThrowable(() -> dimension.record(service, 2)));
            clock.awaitEntered();
            AtomicReference<Thread> updaterThread = new AtomicReference<>();
            var update = executor.submit(() -> {
                updaterThread.set(Thread.currentThread());
                dimension.record(service, 1);
            });
            try {
                awaitBlocked(updaterThread);
            } finally {
                clock.release.countDown();
            }
            assertThat(snapshot.get(3, TimeUnit.SECONDS)).isInstanceOfSatisfying(RecoveryCapacityException.class,
                exception -> assertThat(exception.retryAfterSeconds()).isEqualTo(6));
            update.get(3, TimeUnit.SECONDS);
        }
        assertCapacity(() -> dimension.record(service, 2), 10);
        assertThrottle(() -> dimension.record(service, 1), 6);
    }

    @ParameterizedTest
    @EnumSource(Dimension.class)
    void cleanupCanRemoveAnExpiredKeyBeforeItsWaitingUpdateWithoutLosingTheNewAttempt(Dimension dimension) throws Exception {
        BlockingClock clock = new BlockingClock();
        RecoveryAttemptService service = service(clock, 1, 1, 1, 1);
        dimension.record(service, 1);
        clock.atMillis(10_000);
        clock.armRead(dimension == Dimension.ACCOUNT ? 1 : 2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var cleanup = executor.submit(service::cleanupExpired);
            clock.awaitEntered();
            AtomicReference<Thread> updaterThread = new AtomicReference<>();
            var update = executor.submit(() -> {
                updaterThread.set(Thread.currentThread());
                dimension.record(service, 1);
            });
            try {
                awaitBlocked(updaterThread);
            } finally {
                clock.release.countDown();
            }
            cleanup.get(3, TimeUnit.SECONDS);
            update.get(3, TimeUnit.SECONDS);
        }
        assertThrottle(() -> dimension.record(service, 1), 10);
        assertCapacity(() -> dimension.record(service, 2), 10);
    }

    @Test
    void cancellationAndConcurrentReservationPreserveTheSurvivingAttempt() throws Exception {
        BlockingClock clock = new BlockingClock();
        RecoveryAttemptService service = service(clock, 1, 1, 1, 1);
        var first = service.recordAccountAttempt("name");
        clock.arm();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var cancellation = executor.submit(() -> service.cancelAccountAttempt(first));
            clock.awaitEntered();
            AtomicReference<Thread> updaterThread = new AtomicReference<>();
            var update = executor.submit(() -> {
                updaterThread.set(Thread.currentThread());
                return service.recordAccountAttempt("name");
            });
            try {
                awaitBlocked(updaterThread);
            } finally {
                clock.release.countDown();
            }
            cancellation.get(3, TimeUnit.SECONDS);
            assertThat(update.get(3, TimeUnit.SECONDS).sequence()).isGreaterThan(first.sequence());
        }
        service.cancelAccountAttempt(first);
        assertThrottle(() -> service.recordAccountAttempt("name"), 10);
    }

    private static void awaitBlocked(AtomicReference<Thread> reference) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            Thread thread = reference.get();
            if (thread != null && thread.getState() == Thread.State.BLOCKED) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Concurrent operation did not wait for the map coordination lock");
    }

    private static void assertThrottle(Runnable action, long seconds) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(RecoveryThrottledException.class,
            exception -> assertThat(exception.retryAfterSeconds()).isEqualTo(seconds));
    }

    private static void assertCapacity(Runnable action, long seconds) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(RecoveryCapacityException.class,
            exception -> assertThat(exception.retryAfterSeconds()).isEqualTo(seconds));
    }

    private static RecoveryAttemptService service(Clock clock, int accounts, int ips, int accountCapacity, int ipCapacity) {
        return new RecoveryAttemptService(properties(Duration.ofSeconds(10), accounts, ips, accountCapacity, ipCapacity), clock);
    }

    private static PinProperties properties(Duration window, int accounts, int ips, int accountCapacity, int ipCapacity) {
        return new PinProperties("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", 32, 1, 1, 16, 1, 1,
            window, accounts, ips, accountCapacity, ipCapacity);
    }

    private static InetAddress ip(int lastByte) {
        try {
            return InetAddress.getByAddress(new byte[]{(byte) 192, 0, 2, (byte) lastByte});
        } catch (java.net.UnknownHostException exception) {
            throw new AssertionError(exception);
        }
    }

    private static class MutableClock extends Clock {
        private final AtomicReference<Instant> current = new AtomicReference<>(START);
        final AtomicInteger reads = new AtomicInteger();
        Duration advanceOnRead = Duration.ZERO;

        void atMillis(long millis) {
            current.set(START.plusMillis(millis));
        }

        @Override
        public Instant instant() {
            reads.incrementAndGet();
            return current.getAndUpdate(now -> now.plus(advanceOnRead));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!zone.equals(ZoneOffset.UTC)) {
                throw new UnsupportedOperationException("Test clock supports UTC only");
            }
            return this;
        }
    }

    private static class BlockingClock extends MutableClock {
        private final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger untilBlock = new AtomicInteger(-1);

        void arm() {
            armRead(1);
        }

        void armRead(int read) {
            untilBlock.set(read);
        }

        void awaitEntered() throws InterruptedException {
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        }

        @Override
        public Instant instant() {
            Instant now = super.instant();
            if (untilBlock.decrementAndGet() == 0) {
                entered.countDown();
                try {
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
            return now;
        }
    }
}
