package au.com.dingwall.mark.bitbrush.config;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class PinProductionPolicy {

    private static final int MINIMUM_PRODUCTION_MEMORY_KIB = 19_456;
    private static final int MINIMUM_PRODUCTION_ITERATIONS = 2;
    private static final int MINIMUM_PRODUCTION_HASH_LENGTH = 32;
    private static final int MAXIMUM_PRODUCTION_CONCURRENCY = 2;

    private final PinProperties properties;
    private final Environment environment;

    public PinProductionPolicy(PinProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        PinPepperDecoder.decode(properties.pepper());
        if (properties.recoveryWindow().isZero() || properties.recoveryWindow().isNegative()) {
            throw new IllegalArgumentException("PIN recovery window must be positive");
        }
        if (isProductionProfile() && (properties.memoryKiB() < MINIMUM_PRODUCTION_MEMORY_KIB
            || properties.iterations() < MINIMUM_PRODUCTION_ITERATIONS
            || properties.parallelism() < 1
            || properties.hashLength() < MINIMUM_PRODUCTION_HASH_LENGTH
            || properties.maxConcurrent() > MAXIMUM_PRODUCTION_CONCURRENCY)) {
            throw new IllegalArgumentException("PIN production parameters do not meet the minimum policy");
        }
    }

    private boolean isProductionProfile() {
        return Arrays.stream(environment.getActiveProfiles())
            .anyMatch(profile -> profile.equals("prod") || profile.equals("docker"));
    }
}
