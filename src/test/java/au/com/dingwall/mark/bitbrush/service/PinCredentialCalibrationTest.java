package au.com.dingwall.mark.bitbrush.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in operational measurement: the report contains durations and counts only. */
@EnabledIfEnvironmentVariable(named = "PIN_CALIBRATION", matches = "true")
class PinCredentialCalibrationTest {
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void productionWorkFactorVerifiesWithTwoOverlappingHashes() throws Exception {
        // Catches an unusable production work factor, failed hashes, or loss of concurrent progress.
        byte[] pepper = new byte[32];
        new SecureRandom().nextBytes(pepper);
        PinCredentialCodec codec = new PinCredentialCodec(pepper, 19456, 2, 1, 32);
        Arrays.fill(pepper, (byte) 0);
        var pin = codec.canonicalize("C😀l!");
        assertTrue(codec.verify(pin, codec.hash(pin)), "Calibration warm-up did not verify");

        long[] hashes = new long[10];
        long[] verifications = new long[10];
        for (int index = 0; index < 10; index++) {
            long start = System.nanoTime();
            String hash = codec.hash(pin);
            hashes[index] = System.nanoTime() - start;
            start = System.nanoTime();
            boolean verified = codec.verify(pin, hash);
            verifications[index] = System.nanoTime() - start;
            assertTrue(verified, "Sequential calibration hash did not verify");
        }

        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Measurement> operation = () -> {
            active.incrementAndGet();
            peak.accumulateAndGet(active.get(), Math::max);
            try {
                barrier.await(15, TimeUnit.SECONDS);
                long start = System.nanoTime();
                String hash = codec.hash(pin);
                return new Measurement(start, System.nanoTime(), hash);
            } finally {
                active.decrementAndGet();
            }
        };
        long[] concurrent;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(operation);
            var second = executor.submit(operation);
            Measurement a = first.get(30, TimeUnit.SECONDS);
            Measurement b = second.get(30, TimeUnit.SECONDS);
            assertEquals(2, peak.get(), "Calibration did not admit two simultaneous operations");
            assertTrue(Math.max(a.start, b.start) < Math.min(a.end, b.end),
                "The two real hash calls did not overlap");
            assertTrue(codec.verify(pin, a.hash) && codec.verify(pin, b.hash),
                "Concurrent calibration hashes did not verify");
            concurrent = new long[] {a.end - a.start, b.end - b.start};
        }
        Path report = Path.of("build/reports/pin-calibration.txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, "Argon2id memory_kib=19456 iterations=2 parallelism=1 hash_length=32\n"
            + "sequential_samples=10 peak_overlap=" + peak.get() + "\n"
            + summary("hash", hashes) + summary("verification", verifications) + summary("concurrent_hash", concurrent));
    }

    private String summary(String label, long[] durations) {
        long[] sorted = durations.clone();
        Arrays.sort(sorted);
        double median = (sorted[(sorted.length - 1) / 2] / 2.0 + sorted[sorted.length / 2] / 2.0) / 1_000_000;
        return String.format(Locale.ROOT, "%s_median_ms=%.3f %s_max_ms=%.3f%n",
            label, median, label, sorted[sorted.length - 1] / 1_000_000.0);
    }

    // Deliberately no generated toString: a failing test must never render encoded credentials.
    private static final class Measurement {
        private final long start;
        private final long end;
        private final String hash;

        private Measurement(long start, long end, String hash) {
            this.start = start;
            this.end = end;
            this.hash = hash;
        }
    }
}
