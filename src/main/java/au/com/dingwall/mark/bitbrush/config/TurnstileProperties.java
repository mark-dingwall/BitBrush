package au.com.dingwall.mark.bitbrush.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "turnstile")
public record TurnstileProperties(
    @NotBlank String siteKey,
    @NotBlank String secretKey
) {}
