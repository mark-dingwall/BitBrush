package au.com.dingwall.mark.bitbrush.service;

import au.com.dingwall.mark.bitbrush.config.PinPepperDecoder;
import au.com.dingwall.mark.bitbrush.config.PinProperties;
import au.com.dingwall.mark.bitbrush.exception.PinCapacityException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

@Service
public class PinCredentialService {

    private final PinCredentialCodec codec;
    private final Semaphore permits;
    private final int retryAfterSeconds;
    private final String dummyEncodedHash;

    @Autowired
    public PinCredentialService(PinProperties properties) {
        this(properties, new PinCredentialCodec(PinPepperDecoder.decode(properties.pepper()), properties.memoryKiB(),
            properties.iterations(), properties.parallelism(), properties.hashLength()));
    }

    PinCredentialService(PinProperties properties, PinCredentialCodec codec) {
        this.codec = codec;
        this.permits = new Semaphore(properties.maxConcurrent(), true);
        this.retryAfterSeconds = properties.retryAfterSeconds();
        this.dummyEncodedHash = codec.hash(codec.canonicalize("0000"));
    }

    public PinCredentialCodec.CanonicalPin canonicalize(String rawPin) {
        return codec.canonicalize(rawPin);
    }

    public boolean matchesConfirmation(String pin, String confirmation) {
        PinCredentialCodec.CanonicalPin canonicalPin = canonicalize(pin);
        PinCredentialCodec.CanonicalPin canonicalConfirmation = canonicalize(confirmation);
        return MessageDigest.isEqual(canonicalPin.utf8(), canonicalConfirmation.utf8());
    }

    public String hash(PinCredentialCodec.CanonicalPin pin) {
        return withPermit(() -> codec.hash(pin));
    }

    public boolean verify(PinCredentialCodec.CanonicalPin pin, String encodedHash) {
        return withPermit(() -> codec.verify(pin, encodedHash));
    }

    public void verifyDummy(PinCredentialCodec.CanonicalPin pin) {
        withPermit(() -> codec.verify(pin, dummyEncodedHash));
    }

    public String deriveLegacyPin(String authorId) {
        return codec.deriveLegacyPin(authorId);
    }

    private <T> T withPermit(Supplier<T> operation) {
        if (!permits.tryAcquire()) {
            throw new PinCapacityException(retryAfterSeconds);
        }
        try {
            return operation.get();
        } finally {
            permits.release();
        }
    }
}
