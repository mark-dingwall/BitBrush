package au.com.dingwall.mark.bitbrush.dto;

import au.com.dingwall.mark.bitbrush.validation.CanonicalUuid;
import jakarta.validation.constraints.NotBlank;

public record UserReconnectRequest(@NotBlank @CanonicalUuid String uuid) {}
