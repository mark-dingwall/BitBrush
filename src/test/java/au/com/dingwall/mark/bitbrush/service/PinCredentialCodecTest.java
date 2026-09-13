package au.com.dingwall.mark.bitbrush.service;

import au.com.dingwall.mark.bitbrush.exception.InvalidPinException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PinCredentialCodecTest {

    private final PinCredentialCodec codec = new PinCredentialCodec(new byte[32], 32, 1, 1, 32);

    @ParameterizedTest(name = "[{index}]")
    @MethodSource("canonicalPins")
    void canonicalizesValidPins(String rawPin, String canonicalValue) {
        PinCredentialCodec.CanonicalPin pin = codec.canonicalize(rawPin);

        assertThat(pin.value()).isEqualTo(canonicalValue);
        assertThat(pin.utf8()).containsExactly(canonicalValue.getBytes(StandardCharsets.UTF_8));
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
        assertThat(codec.canonicalize("Ab!9").value()).isNotEqualTo(codec.canonicalize("aB!9").value());
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
        assertThat(codec.canonicalize("A😀b!").value()).isEqualTo("A😀b!");
    }

    @Test
    void canonicalPinDefensivelyCopiesUtf8Bytes() {
        PinCredentialCodec.CanonicalPin pin = codec.canonicalize("Ab!9");
        byte[] copy = pin.utf8();
        copy[0] = 0;

        assertThat(pin.utf8()[0]).isEqualTo((byte) 'A');
    }

    @Test
    void derivesStableFourDigitLegacyPin() {
        assertThat(codec.deriveLegacyPin("legacy-author-id")).matches("\\d{4}");
        assertThat(codec.deriveLegacyPin("legacy-author-id"))
            .isEqualTo(codec.deriveLegacyPin("legacy-author-id"));
    }

    @Test
    void keepsLegacyDerivationSeparateFromCredentialHashing() {
        String derived = codec.deriveLegacyPin("legacy-author-id");

        assertThat(codec.verify(codec.canonicalize(derived), codec.hash(codec.canonicalize(derived)))).isTrue();
    }

    private void assertInvalid(String rawPin) {
        assertThatThrownBy(() -> codec.canonicalize(rawPin)).isInstanceOf(InvalidPinException.class);
    }
}
