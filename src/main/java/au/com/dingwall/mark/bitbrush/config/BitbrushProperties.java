package au.com.dingwall.mark.bitbrush.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Min;

@Validated
@ConfigurationProperties(prefix = "bitbrush")
public record BitbrushProperties(
    Canvas canvas,
    Placement placement
) {
    public record Canvas(
        @Min(1) int width,
        @Min(1) int height
    ) {}

    public record Placement(
        @Min(1) int earnRateSeconds,
        @Min(1) int maxBanked,
        @Min(1) int startingBalance
    ) {}
}
