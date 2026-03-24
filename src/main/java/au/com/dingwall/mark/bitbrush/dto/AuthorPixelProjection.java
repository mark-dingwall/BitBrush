package au.com.dingwall.mark.bitbrush.dto;

/**
 * Interface projection for author pixel coordinate queries.
 *
 * Getter names match JPQL aliases: SELECT p.x AS x, p.y AS y
 */
public interface AuthorPixelProjection {
    int getX();
    int getY();
}
