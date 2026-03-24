package au.com.dingwall.mark.bitbrush.dto;

import java.time.Instant;
import java.util.List;

/**
 * Response body for GET /api/pixels/{x}/{y}/info.
 *
 * Contains the most recent author info at (x,y) and all current pixel
 * positions by that author (for highlight overlay on the canvas).
 *
 * The nested AuthorPixelCoordinate record avoids leaking the projection
 * interface to the API response.
 */
public record PixelInfoResponse(
        String authorUuid,
        String username,
        Instant placedAt,
        List<AuthorPixelCoordinate> authorPixels
) {
    public record AuthorPixelCoordinate(int x, int y) {}
}
