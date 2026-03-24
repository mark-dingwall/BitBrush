package au.com.dingwall.mark.bitbrush.dto;

/**
 * Interface projection for GROUP BY color distribution query.
 *
 * Getter names (minus "get" prefix, camelCase) must match JPQL aliases:
 *   SELECT p.paletteIndex AS paletteIndex, COUNT(p) AS pixelCount
 */
public interface ColorCountProjection {
    int getPaletteIndex();
    long getPixelCount();
}
