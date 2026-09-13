package au.com.dingwall.mark.bitbrush.config;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PinPepperDecoderTest {

    @Test
    void decodesThirtyTwoBytePepper() {
        byte[] pepper = new byte[32];
        pepper[0] = 7;

        assertThat(PinPepperDecoder.decode(Base64.getEncoder().encodeToString(pepper)))
            .containsExactly(pepper);
    }

    @Test
    void decodesPepperLongerThanMinimum() {
        byte[] pepper = new byte[48];
        pepper[47] = 9;

        assertThat(PinPepperDecoder.decode(Base64.getEncoder().encodeToString(pepper)))
            .containsExactly(pepper);
    }

    @Test
    void rejectsMissingMalformedAndTooShortPepper() {
        assertThatIllegalArgumentException().isThrownBy(() -> PinPepperDecoder.decode(null));
        assertThatIllegalArgumentException().isThrownBy(() -> PinPepperDecoder.decode("not base64!"));
        assertThatIllegalArgumentException().isThrownBy(() -> PinPepperDecoder.decode(
            Base64.getEncoder().encodeToString(new byte[31])));
    }
}
