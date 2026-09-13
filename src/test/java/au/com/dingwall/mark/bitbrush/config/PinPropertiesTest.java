package au.com.dingwall.mark.bitbrush.config;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PinPropertiesTest {

    @Test
    void rejectsInvalidConfigurationValues() {
        PinProperties properties = new PinProperties(" ", 7, 0, 0, 15, 0, 0,
            null, 0, 0, 0, 0);

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties)).isNotEmpty();
        }
    }

    @Test
    void acceptsACompleteValidConfiguration() {
        PinProperties properties = validProperties();

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties)).isEmpty();
        }
    }

    static PinProperties validProperties() {
        return new PinProperties("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", 32, 1, 1, 16,
            1, 1, Duration.ofMinutes(15), 5, 20, 10_000, 10_000);
    }
}
