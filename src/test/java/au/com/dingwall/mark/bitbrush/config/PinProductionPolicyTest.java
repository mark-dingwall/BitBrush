package au.com.dingwall.mark.bitbrush.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PinProductionPolicyTest {

    @Test
    void acceptsCheapParametersOutsideProductionProfiles() {
        MockEnvironment environment = activeProfile("test");

        assertThatCode(() -> new PinProductionPolicy(PinPropertiesTest.validProperties(),
            environment).validate()).doesNotThrowAnyException();
    }

    @Test
    void rejectsWeakenedParametersInProductionProfiles() {
        MockEnvironment environment = activeProfile("prod");

        assertThatIllegalArgumentException().isThrownBy(() -> new PinProductionPolicy(
            PinPropertiesTest.validProperties(), environment).validate());
    }

    @Test
    void rejectsExcessiveConcurrencyInDocker() {
        PinProperties properties = new PinProperties("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            19_456, 2, 1, 32, 3, 1, Duration.ofMinutes(15), 5, 20, 10_000, 10_000);

        MockEnvironment environment = activeProfile("docker");

        assertThatIllegalArgumentException().isThrownBy(() -> new PinProductionPolicy(properties,
            environment).validate());
    }

    @Test
    void rejectsNonPositiveRecoveryWindowInEveryProfile() {
        PinProperties properties = new PinProperties("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            32, 1, 1, 16, 1, 1, Duration.ZERO, 5, 20, 10_000, 10_000);

        MockEnvironment environment = activeProfile("test");

        assertThatIllegalArgumentException().isThrownBy(() -> new PinProductionPolicy(properties,
            environment).validate());
    }

    private MockEnvironment activeProfile(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }
}
