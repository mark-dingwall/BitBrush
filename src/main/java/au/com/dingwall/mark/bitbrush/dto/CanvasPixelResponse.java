package au.com.dingwall.mark.bitbrush.dto;

/**
 * Response DTO for a single pixel on the canvas.
 * No validation annotations — this is outbound data, not input.
 */
public record CanvasPixelResponse(int x, int y, String color) {
}
