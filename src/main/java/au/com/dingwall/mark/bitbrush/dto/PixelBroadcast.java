package au.com.dingwall.mark.bitbrush.dto;

/**
 * Broadcast payload for a single pixel placement via WebSocket/STOMP.
 *
 * Contains the resolved hex color string so clients can paint directly
 * into the ImageData buffer without performing a palette lookup.
 *
 * authorUuid enables smart cache invalidation on the client: when a pixel
 * broadcast arrives, the client can check if the author matches the currently
 * inspected pixel and refresh the info panel without a separate API call.
 *
 * Published to /topic/pixels after each pixel save in PixelService.
 */
public record PixelBroadcast(int x, int y, String color, String authorUuid, boolean erased) {
}
