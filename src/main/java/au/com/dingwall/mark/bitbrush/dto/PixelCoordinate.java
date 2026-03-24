package au.com.dingwall.mark.bitbrush.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * A single pixel coordinate within the 250x250 canvas.
 * Valid indices: 0-249 (inclusive).
 */
public record PixelCoordinate(
        @Min(0) @Max(249) int x,
        @Min(0) @Max(249) int y
) {
}
