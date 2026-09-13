package au.com.dingwall.mark.bitbrush.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "pin")
public record PinProperties(
    @NotBlank String pepper,
    @Min(8) int memoryKiB,
    @Min(1) int iterations,
    @Min(1) int parallelism,
    @Min(16) int hashLength,
    @Min(1) int maxConcurrent,
    @Min(1) int retryAfterSeconds,
    @NotNull Duration recoveryWindow,
    @Min(1) int accountLimit,
    @Min(1) int ipLimit,
    @Min(1) int accountCapacity,
    @Min(1) int ipCapacity) {
}
