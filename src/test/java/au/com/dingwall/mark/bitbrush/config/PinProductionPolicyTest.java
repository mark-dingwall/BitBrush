package au.com.dingwall.mark.bitbrush.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
            19_456, 2, 1, 32, 3, 1, Duration.ofMinutes(15), 5, 20, 10_000, 10_000, "");

        MockEnvironment environment = activeProfile("docker");

        assertThatIllegalArgumentException().isThrownBy(() -> new PinProductionPolicy(properties,
            environment).validate());
    }

    @ParameterizedTest
    @CsvSource({"19455,2,1,32,2", "19456,1,1,32,2", "19456,2,0,32,2", "19456,2,1,31,2", "19456,2,1,32,3"})
    void rejectsEachIndependentProductionPolicyViolation(int memory, int iterations, int parallelism, int hashLength, int concurrency) {
        // Catches removal of any single work-factor or concurrency guard hidden by short-circuiting.
        PinProperties properties = new PinProperties("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            memory, iterations, parallelism, hashLength, concurrency, 1, Duration.ofMinutes(15),
            5, 20, 10_000, 10_000, "");
        for (String profile : new String[] {"prod", "docker"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new PinProductionPolicy(properties,
                activeProfile(profile)).validate());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "docker"})
    void acceptsTheCalibratedProductionBoundary(String profile) {
        PinProperties properties = new PinProperties("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            19_456, 2, 1, 32, 2, 1, Duration.ofMinutes(15), 5, 20, 10_000, 10_000, "");
        assertThatCode(() -> new PinProductionPolicy(properties, activeProfile(profile)).validate())
            .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveRecoveryWindowInEveryProfile(long seconds) {
        PinProperties properties = new PinProperties("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            19_456, 2, 1, 32, 2, 1, Duration.ofSeconds(seconds), 5, 20, 10_000, 10_000, "");
        for (String profile : new String[] {"test", "dev", "prod", "docker"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new PinProductionPolicy(properties,
                activeProfile(profile)).validate());
        }
    }

    private MockEnvironment activeProfile(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }
}
