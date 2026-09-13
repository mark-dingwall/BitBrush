package au.com.dingwall.mark.bitbrush.service;

import au.com.dingwall.mark.bitbrush.config.PinProperties;
import au.com.dingwall.mark.bitbrush.exception.PinCapacityException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PinCredentialServiceTest {

    private final PinProperties properties = new PinProperties(
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", 32, 1, 1, 16, 1, 7,
        Duration.ofMinutes(15), 5, 20, 10_000, 10_000, "");

    @Test
    void hashesSamePinWithDistinctSaltsAndVerifiesOnlyTheRightCaseAndPepper() {
        PinCredentialService service = new PinCredentialService(properties);
        PinCredentialCodec.CanonicalPin pin = service.canonicalize("Ab!9");
        String first = service.hash(pin);
        String second = service.hash(pin);
        PinCredentialService wrongPepper = new PinCredentialService(new PinProperties(
            "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=", 32, 1, 1, 16, 1, 7,
            Duration.ofMinutes(15), 5, 20, 10_000, 10_000, ""));

        assertThat(!first.equals(second)).withFailMessage("Repeated hashing reused a salt").isTrue();
        assertThat(service.verify(pin, first)).isTrue();
        assertThat(service.verify(service.canonicalize("aB!9"), first)).isFalse();
        assertThat(wrongPepper.verify(wrongPepper.canonicalize("Ab!9"), first)).isFalse();
        assertThat(first.startsWith("$argon2id$v=19$m=32,t=1,p=1$"))
            .withFailMessage("Credential hash does not use the configured Argon2 parameters").isTrue();
        assertThat(first.contains("Ab!9")).isFalse();
        assertThat(first.contains(properties.pepper())).isFalse();
    }

    @Test
    void comparesConfirmationAfterCanonicalization() {
        PinCredentialService service = new PinCredentialService(properties);

        assertThat(service.matchesConfirmation("Ae\u0301 !", "Aé !")).isTrue();
        assertThat(service.matchesConfirmation("Ab!9", "aB!9")).isFalse();
    }

    @Test
    void sharesOneImmediatePermitBetweenRealAndDummyWork() throws Exception {
        BlockingCodec codec = new BlockingCodec();
        PinCredentialService service = new PinCredentialService(properties, codec);
        PinCredentialCodec.CanonicalPin pin = service.canonicalize("Ab!9");
        codec.blockCalls.set(true);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var activeHash = executor.submit(() -> service.hash(pin));
            assertThat(codec.entered.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> service.verifyDummy(pin))
                .isInstanceOf(PinCapacityException.class)
                .satisfies(exception -> assertThat(((PinCapacityException) exception).retryAfterSeconds()).isEqualTo(7));

            codec.release.countDown();
            assertThat(activeHash.get(2, TimeUnit.SECONDS)).isEqualTo("hash");
        }
    }

    @Test
    void releasesPermitAfterCodecFailure() {
        BlockingCodec codec = new BlockingCodec();
        PinCredentialService service = new PinCredentialService(properties, codec);
        PinCredentialCodec.CanonicalPin pin = service.canonicalize("Ab!9");
        codec.failHash.set(true);

        assertThatThrownBy(() -> service.hash(pin)).isInstanceOf(IllegalStateException.class);
        codec.failHash.set(false);

        assertThat(service.hash(pin)).isEqualTo("hash");
    }

    @Test
    void doesNotWaitOrClearAnInterruptedCallersInterruptFlag() {
        BlockingCodec codec = new BlockingCodec();
        PinCredentialService service = new PinCredentialService(properties, codec);
        PinCredentialCodec.CanonicalPin pin = service.canonicalize("Ab!9");

        Thread.currentThread().interrupt();
        try {
            assertThatCode(() -> service.hash(pin)).doesNotThrowAnyException();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private static class BlockingCodec extends PinCredentialCodec {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean blockCalls = new AtomicBoolean();
        private final AtomicBoolean failHash = new AtomicBoolean();

        BlockingCodec() {
            super(new byte[32], 32, 1, 1, 16);
        }

        @Override
        public String hash(CanonicalPin canonicalPin) {
            if (failHash.get()) {
                throw new IllegalStateException("test failure");
            }
            awaitIfBlocked();
            return "hash";
        }

        @Override
        public boolean verify(CanonicalPin canonicalPin, String encodedHash) {
            awaitIfBlocked();
            return true;
        }

        @Override
        public CanonicalPin canonicalize(String rawPin) {
            return new CanonicalPin(rawPin, rawPin.getBytes(StandardCharsets.UTF_8));
        }

        private void awaitIfBlocked() {
            if (!blockCalls.get()) {
                return;
            }
            entered.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test interrupted", exception);
            }
        }
    }
}
