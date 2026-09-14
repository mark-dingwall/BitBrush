package au.com.dingwall.mark.bitbrush.service;

import au.com.dingwall.mark.bitbrush.exception.InvalidPinException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PinCredentialCodecTest {

    private final PinCredentialCodec codec = new PinCredentialCodec(new byte[32], 32, 1, 1, 32);

    @ParameterizedTest(name = "[{index}]")
    @MethodSource("canonicalPins")
    void canonicalizesValidPins(String rawPin, String canonicalValue) {
        PinCredentialCodec.CanonicalPin pin = codec.canonicalize(rawPin);

        assertThat(pin.value().equals(canonicalValue))
            .withFailMessage("canonical PIN did not match its expected value")
            .isTrue();
        assertThat(Arrays.equals(pin.utf8(), canonicalValue.getBytes(StandardCharsets.UTF_8)))
            .withFailMessage("canonical PIN UTF-8 bytes did not match their expected value")
            .isTrue();
    }

    static Stream<Arguments> canonicalPins() {
        return Stream.of(
            Arguments.of("Ab!9", "Ab!9"),
            Arguments.of("A😀b!", "A😀b!"),
            Arguments.of("Ж中؟ß", "Ж中؟ß"),
            Arguments.of("Ae\u0301 !", "Aé !"),
            Arguments.of("A\u0000b!", "A b!"),
            Arguments.of("A\u200Eb!", "A b!"),
            Arguments.of("A\u00A0b!", "A b!"),
            Arguments.of("A\u2028b!", "A b!"),
            Arguments.of("A\u2029b!", "A b!"),
            Arguments.of(" Ab!", " Ab!"),
            Arguments.of("Ab! ", "Ab! ")
        );
    }

    @Test
    void preservesCaseAsPartOfCredential() {
        assertThat(!codec.canonicalize("Ab!9").value().equals(codec.canonicalize("aB!9").value()))
            .withFailMessage("PIN case was not preserved")
            .isTrue();
    }

    @Test
    void rejectsValuesOutsideTheExactFourCodePointLimit() {
        assertInvalid("Ab!");
        assertInvalid("Ab!90");
        assertInvalid("A\u0000!");
        assertInvalid("A\u0000!90");
        assertInvalid(null);
        assertInvalid("");
        assertInvalid("a".repeat(257));
    }

    @Test
    void rejectsUnpairedSurrogatesButAcceptsAPair() {
        assertInvalid("A\uD800b!");
        assertInvalid("A\uDC00b!");
        assertThat(codec.canonicalize("A😀b!").value().equals("A😀b!"))
            .withFailMessage("a paired supplementary code point was not preserved")
            .isTrue();
    }

    @Test
    void canonicalPinDefensivelyCopiesUtf8Bytes() {
        PinCredentialCodec.CanonicalPin pin = codec.canonicalize("Ab!9");
        byte[] copy = pin.utf8();
        copy[0] = 0;

        assertThat(pin.utf8()[0] == (byte) 'A')
            .withFailMessage("canonical PIN UTF-8 bytes were not defensively copied")
            .isTrue();
    }

    @Test
    void derivesStableFourDigitLegacyPin() {
        assertThat(codec.deriveLegacyPin("legacy-author-id").matches("\\d{4}"))
            .withFailMessage("legacy PIN was not four decimal digits")
            .isTrue();
        assertThat(codec.deriveLegacyPin("legacy-author-id")
            .equals(codec.deriveLegacyPin("legacy-author-id")))
            .withFailMessage("legacy PIN derivation was not stable")
            .isTrue();
    }

    @Test
    void derivedLegacyPinCanBeHashedAndVerified() {
        String derived = codec.deriveLegacyPin("legacy-author-id");

        assertThat(codec.verify(codec.canonicalize(derived), codec.hash(codec.canonicalize(derived)))).isTrue();
    }

    @Test
    void credentialHashUsesTheVersionedPinDomainAndRejectsTheLegacyDomain() throws Exception {
        // Catches changing or sharing the two domain prefixes, independently of codec.verify().
        String hash = codec.hash(codec.canonicalize("Ab!9"));
        var encoder = new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16, 32, 1, 32, 1);
        assertThat(encoder.matches(keyedInput("bitbrush-pin-v1\0"), hash))
            .withFailMessage("Credential hash did not use the PIN protocol domain").isTrue();
        assertThat(encoder.matches(keyedInput("bitbrush-legacy-pin-v1\0"), hash))
            .withFailMessage("Credential hash reused the legacy protocol domain").isFalse();
    }

    @Test
    void legacyDerivationMatchesAnIndependentProtocolVector() {
        // Fixed HMAC-SHA-256/rejection-sampling vector, also used by PostgreSQL migration tests.
        byte[] pepper = "migration-test-unique-pepper-32-bytes-2026".getBytes(StandardCharsets.UTF_8);
        var vectorCodec = new PinCredentialCodec(pepper, 32, 1, 1, 32);
        assertThat(vectorCodec.deriveLegacyPin("10000000-0000-4000-8000-000000000001").equals("9912"))
            .withFailMessage("Legacy derivation did not match the independent protocol vector").isTrue();
    }

    private String keyedInput(String domain) throws Exception {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(new byte[32], "HmacSHA256"));
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            mac.doFinal((domain + "Ab!9").getBytes(StandardCharsets.UTF_8)));
    }

    private void assertInvalid(String rawPin) {
        assertThatThrownBy(() -> codec.canonicalize(rawPin)).isInstanceOf(InvalidPinException.class);
    }
}
