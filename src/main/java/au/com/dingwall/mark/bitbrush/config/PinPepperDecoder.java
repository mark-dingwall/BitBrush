package au.com.dingwall.mark.bitbrush.config;

import java.util.Base64;

public final class PinPepperDecoder {

    private static final int MINIMUM_PEPPER_BYTES = 32;

    private PinPepperDecoder() {
    }

    public static byte[] decode(String encodedPepper) {
        if (encodedPepper == null || encodedPepper.isBlank()) {
            throw new IllegalArgumentException("PIN pepper is required");
        }

        final byte[] pepper;
        try {
            pepper = Base64.getDecoder().decode(encodedPepper);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("PIN pepper must be valid Base64", exception);
        }
        if (pepper.length < MINIMUM_PEPPER_BYTES) {
            throw new IllegalArgumentException("PIN pepper must decode to at least 32 bytes");
        }
        return pepper;
    }
}
