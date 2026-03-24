package au.com.dingwall.mark.bitbrush.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Request body for placing one or more pixels on the canvas.
 */
public record PixelPlacementRequest(
        @NotEmpty @Valid List<PixelCoordinate> pixels,
        @Min(0) @Max(215) int paletteIndex,
        @NotBlank String authorUuid
) {
}
