package au.com.dingwall.mark.bitbrush.config;

import jakarta.validation.Validation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnstilePropertiesTest {
    private static final String SECRET_VARIABLE = "TURNSTILE_SECRET_KEY";
    private static final String EXPLICIT_FIXTURE = "synthetic-turnstile-secret";
    private static final String TEST_DEFAULT = "1x0000000000000000000000000000000AA";

    @ParameterizedTest
    @ValueSource(strings = {"prod", "docker"})
    void requiresAnExplicitNonBlankSecret(String profile) throws IOException {
        MockEnvironment environment = activeProfile(profile);
        assertFalse(isValid(environment), "Missing Turnstile secret must be rejected");

        environment.setProperty(SECRET_VARIABLE, "");
        assertFalse(isValid(environment), "Empty Turnstile secret must be rejected");

        environment.setProperty(SECRET_VARIABLE, EXPLICIT_FIXTURE);
        assertTrue(isValid(environment), "Explicit fixture configuration must be valid");
        assertTrue(EXPLICIT_FIXTURE.equals(configuredSecret(environment)), "Explicit secret must be used");
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void allowsLocalDefaultAndExplicitOverride(String profile) throws IOException {
        MockEnvironment environment = activeProfile(profile);
        assertTrue(isValid(environment), "Local Turnstile defaults must be valid");
        assertTrue(TEST_DEFAULT.equals(configuredSecret(environment)), "Local test secret must be the default");

        environment.setProperty(SECRET_VARIABLE, EXPLICIT_FIXTURE);
        assertTrue(EXPLICIT_FIXTURE.equals(configuredSecret(environment)), "Explicit secret must override the local default");
    }

    private MockEnvironment activeProfile(String profile) throws IOException {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        environment.getPropertySources().addLast(new ResourcePropertySource("classpath:application-" + profile + ".properties"));
        environment.getPropertySources().addLast(new ResourcePropertySource("classpath:application.properties"));
        return environment;
    }

    private boolean isValid(MockEnvironment environment) {
        var properties = new TurnstileProperties(environment.getProperty("turnstile.site-key"), configuredSecret(environment));
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator().validate(properties).isEmpty();
        }
    }

    private String configuredSecret(MockEnvironment environment) {
        return environment.getProperty("turnstile.secret-key");
    }
}
