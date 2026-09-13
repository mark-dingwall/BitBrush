package au.com.dingwall.mark.bitbrush.dto;

import jakarta.validation.constraints.*;

public record UserRecoveryRequest(
    @NotBlank @Size(min = 3, max = 30)
    @Pattern(regexp = "[a-zA-Z0-9_-]+", message = "Invalid username")
    String username,
    @NotNull @Size(max = 256) String pin
) {}
