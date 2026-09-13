package au.com.dingwall.mark.bitbrush.service;

import au.com.dingwall.mark.bitbrush.exception.InvalidPinException;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

public class PinCredentialCodec {

    private static final byte[] PIN_DOMAIN = "bitbrush-pin-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LEGACY_PIN_DOMAIN = "bitbrush-legacy-pin-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final long LEGACY_PIN_UPPER_BOUND = (1L << 32) / 10_000 * 10_000;

    private final byte[] pepper;
    private final Argon2PasswordEncoder encoder;

    public PinCredentialCodec(byte[] pepper, int memoryKiB, int iterations, int parallelism, int hashLength) {
        this.pepper = Arrays.copyOf(pepper, pepper.length);
        this.encoder = new Argon2PasswordEncoder(16, hashLength, parallelism, memoryKiB, iterations);
    }

    public CanonicalPin canonicalize(String rawPin) {
        if (rawPin == null || rawPin.isEmpty() || rawPin.length() > 256) {
            throw new InvalidPinException();
        }
        rejectUnpairedSurrogates(rawPin);

        String normalized = Normalizer.normalize(rawPin, Normalizer.Form.NFC);
        StringBuilder canonical = new StringBuilder(normalized.length());
        normalized.codePoints().forEach(codePoint -> {
            if (isReplacedCategory(Character.getType(codePoint))) {
                canonical.append(' ');
            } else {
                canonical.appendCodePoint(codePoint);
            }
        });
        if (canonical.codePointCount(0, canonical.length()) != 4) {
            throw new InvalidPinException();
        }
        String value = canonical.toString();
        return new CanonicalPin(value, value.getBytes(StandardCharsets.UTF_8));
    }

    public String hash(CanonicalPin canonicalPin) {
        char[] argonInput = argonInput(canonicalPin);
        try {
            return encoder.encode(CharBuffer.wrap(argonInput));
        } finally {
            Arrays.fill(argonInput, '\0');
        }
    }

    public boolean verify(CanonicalPin canonicalPin, String encodedHash) {
        char[] argonInput = argonInput(canonicalPin);
        try {
            return encoder.matches(CharBuffer.wrap(argonInput), encodedHash);
        } finally {
            Arrays.fill(argonInput, '\0');
        }
    }

    public String deriveLegacyPin(String authorId) {
        if (authorId == null) {
            throw new IllegalArgumentException("Author id is required");
        }
        byte[] authorBytes = authorId.getBytes(StandardCharsets.UTF_8);
        int counter = 0;
        try {
            while (true) {
                byte[] digest = legacyDigest(authorBytes, counter++);
                try {
                    for (int offset = 0; offset + Integer.BYTES <= digest.length; offset += Integer.BYTES) {
                        long candidate = Integer.toUnsignedLong(ByteBuffer.wrap(digest, offset, Integer.BYTES).getInt());
                        if (candidate < LEGACY_PIN_UPPER_BOUND) {
                            return String.format(Locale.ROOT, "%04d", candidate % 10_000);
                        }
                    }
                } finally {
                    Arrays.fill(digest, (byte) 0);
                }
            }
        } finally {
            Arrays.fill(authorBytes, (byte) 0);
        }
    }

    private char[] argonInput(CanonicalPin canonicalPin) {
        byte[] keyed = null;
        byte[] encodedKeyed = null;
        byte[] canonicalUtf8 = null;
        try {
            canonicalUtf8 = canonicalPin.utf8();
            keyed = hmac(PIN_DOMAIN, canonicalUtf8);
            encodedKeyed = Base64.getUrlEncoder().withoutPadding().encode(keyed);
            return new String(encodedKeyed, StandardCharsets.US_ASCII).toCharArray();
        } finally {
            if (canonicalUtf8 != null) {
                Arrays.fill(canonicalUtf8, (byte) 0);
            }
            if (keyed != null) {
                Arrays.fill(keyed, (byte) 0);
            }
            if (encodedKeyed != null) {
                Arrays.fill(encodedKeyed, (byte) 0);
            }
        }
    }

    private byte[] legacyDigest(byte[] authorBytes, int counter) {
        Mac mac = newMac();
        mac.update(LEGACY_PIN_DOMAIN);
        mac.update(authorBytes);
        if (counter > 0) {
            mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(counter).array());
        }
        return mac.doFinal();
    }

    private byte[] hmac(byte[] domain, byte[] input) {
        Mac mac = newMac();
        mac.update(domain);
        return mac.doFinal(input);
    }

    private Mac newMac() {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return mac;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to initialize PIN credential processing", exception);
        }
    }

    private void rejectUnpairedSurrogates(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 == value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new InvalidPinException();
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new InvalidPinException();
            }
        }
    }

    private boolean isReplacedCategory(int category) {
        return category == Character.CONTROL
            || category == Character.FORMAT
            || category == Character.SPACE_SEPARATOR
            || category == Character.LINE_SEPARATOR
            || category == Character.PARAGRAPH_SEPARATOR;
    }

    public record CanonicalPin(String value, byte[] utf8) {
        public CanonicalPin {
            utf8 = Arrays.copyOf(utf8, utf8.length);
        }

        @Override
        public byte[] utf8() {
            return Arrays.copyOf(utf8, utf8.length);
        }
    }
}
