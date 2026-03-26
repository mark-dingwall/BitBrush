package au.com.dingwall.mark.bitbrush.repository;

import au.com.dingwall.mark.bitbrush.dto.AuthorPixelProjection;
import au.com.dingwall.mark.bitbrush.dto.ColorCountProjection;
import au.com.dingwall.mark.bitbrush.model.Pixel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Persistence layer for the pixel placement log.
 *
 * Last-writer-wins: returns the most recently placed pixel at each (x,y) coordinate.
 * Phase 5 note: profile this query if canvas data grows large — consider a dedicated
 * current-state table.
 */
public interface PixelRepository extends JpaRepository<Pixel, Long> {

    @Query("""
            SELECT p FROM Pixel p
            WHERE p.paletteIndex <> 0
            AND p.placedAt = (
                SELECT MAX(p2.placedAt) FROM Pixel p2
                WHERE p2.x = p.x AND p2.y = p.y
            )
            """)
    List<Pixel> findCurrentCanvasState();

    Optional<Pixel> findFirstByXAndYOrderByPlacedAtDesc(int x, int y);

    @Query("""
            SELECT p.x AS x, p.y AS y FROM Pixel p
            WHERE p.authorUuid = :authorUuid
            AND p.paletteIndex <> 0
            AND p.placedAt = (
                SELECT MAX(p2.placedAt) FROM Pixel p2
                WHERE p2.x = p.x AND p2.y = p.y
            )
            """)
    List<AuthorPixelProjection> findCurrentPixelsByAuthor(@Param("authorUuid") String authorUuid);

    @Query("""
            SELECT COUNT(p) FROM Pixel p
            WHERE p.paletteIndex <> 0
            AND p.placedAt = (
                SELECT MAX(p2.placedAt) FROM Pixel p2
                WHERE p2.x = p.x AND p2.y = p.y
            )
            """)
    long countCurrentPixels();

    @Query("""
            SELECT p.paletteIndex AS paletteIndex, COUNT(p) AS pixelCount
            FROM Pixel p
            WHERE p.paletteIndex <> 0
            AND p.placedAt = (
                SELECT MAX(p2.placedAt) FROM Pixel p2
                WHERE p2.x = p.x AND p2.y = p.y
            )
            GROUP BY p.paletteIndex
            ORDER BY COUNT(p) DESC
            """)
    List<ColorCountProjection> findColorDistribution();
}
