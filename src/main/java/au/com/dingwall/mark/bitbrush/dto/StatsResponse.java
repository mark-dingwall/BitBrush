package au.com.dingwall.mark.bitbrush.dto;

import java.util.List;

/**
 * Response body for GET /api/stats.
 *
 * Provides aggregate canvas statistics: total pixel count and
 * per-color distribution ordered by count descending.
 */
public record StatsResponse(
        long totalPixels,
        List<ColorCount> colorDistribution
) {
    public record ColorCount(int paletteIndex, long pixelCount) {}
}
