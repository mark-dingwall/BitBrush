package au.com.dingwall.mark.bitbrush.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for registering a new user UUID-to-username mapping.
 */
public record UserRegistrationRequest(
        @NotBlank String uuid,
        @NotBlank
        @Size(min = 3, max = 30)
        @Pattern(regexp = "[a-zA-Z0-9_-]+", message = "Username may only contain letters, numbers, underscores, and hyphens")
        String username
) {
}
