package au.com.dingwall.mark.bitbrush.repository;

import au.com.dingwall.mark.bitbrush.dto.AuthorPixelProjection;
import au.com.dingwall.mark.bitbrush.dto.ColorCountProjection;
import au.com.dingwall.mark.bitbrush.model.Pixel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository slice tests for Pixel persistence (PIXL-03, PIXL-04).
 *
 * Uses @DataJpaTest so only the JPA layer is loaded (in-memory H2).
 */
@DataJpaTest
@ActiveProfiles("test")
class PixelRepositoryTest {

    @Autowired
    private PixelRepository pixelRepository;

    @Test
    void savedPixelHasAllFields() {
        Pixel pixel = new Pixel();
        pixel.setX(10);
        pixel.setY(20);
        pixel.setPaletteIndex(5);
        pixel.setAuthorUuid("uuid-1");
        pixel.setPlacedAt(Instant.parse("2026-01-01T12:00:00Z"));

        Pixel saved = pixelRepository.save(pixel);
        Pixel reloaded = pixelRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getX()).isEqualTo(10);
        assertThat(reloaded.getY()).isEqualTo(20);
        assertThat(reloaded.getPaletteIndex()).isEqualTo(5);
        assertThat(reloaded.getAuthorUuid()).isEqualTo("uuid-1");
        assertThat(reloaded.getPlacedAt()).isEqualTo(Instant.parse("2026-01-01T12:00:00Z"));
    }

    @Test
    void findCurrentCanvasStateReturnsLatestPixelPerCoordinate() {
        Instant first = Instant.parse("2026-01-01T12:00:00Z");
        Instant second = first.plusSeconds(1);

        Pixel p1 = new Pixel();
        p1.setX(5);
        p1.setY(5);
        p1.setPaletteIndex(1);
        p1.setAuthorUuid("uuid-1");
        p1.setPlacedAt(first);

        Pixel p2 = new Pixel();
        p2.setX(5);
        p2.setY(5);
        p2.setPaletteIndex(7);
        p2.setAuthorUuid("uuid-2");
        p2.setPlacedAt(second);

        pixelRepository.save(p1);
        pixelRepository.save(p2);

        List<Pixel> result = pixelRepository.findCurrentCanvasState();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPaletteIndex()).isEqualTo(7);
    }

    @Test
    void findFirstByXAndYOrderByPlacedAtDesc_returnsLatest() {
        Instant first = Instant.parse("2026-01-01T12:00:00Z");
        Instant second = first.plusSeconds(1);

        Pixel p1 = makePixel(3, 3, 1, "uuid-1", first);
        Pixel p2 = makePixel(3, 3, 2, "uuid-2", second);
        pixelRepository.save(p1);
        pixelRepository.save(p2);

        Optional<Pixel> result = pixelRepository.findFirstByXAndYOrderByPlacedAtDesc(3, 3);

        assertThat(result).isPresent();
        assertThat(result.get().getPlacedAt()).isEqualTo(second);
        assertThat(result.get().getPaletteIndex()).isEqualTo(2);
    }

    @Test
    void findCurrentPixelsByAuthor_returnsOnlyCurrentPixels() {
        Instant first = Instant.parse("2026-01-01T12:00:00Z");
        Instant second = first.plusSeconds(1);

        // Author A places at (0,0)
        pixelRepository.save(makePixel(0, 0, 1, "author-a", first));
        // Author A places at (1,1)
        pixelRepository.save(makePixel(1, 1, 1, "author-a", first));
        // Author B overwrites (0,0)
        pixelRepository.save(makePixel(0, 0, 2, "author-b", second));

        List<AuthorPixelProjection> authorAPixels = pixelRepository.findCurrentPixelsByAuthor("author-a");

        // Author A only has (1,1) as current — (0,0) was overwritten by author B
        assertThat(authorAPixels).hasSize(1);
        assertThat(authorAPixels.get(0).getX()).isEqualTo(1);
        assertThat(authorAPixels.get(0).getY()).isEqualTo(1);
    }

    @Test
    void countCurrentPixels_countsDistinctCoordinates() {
        Instant first = Instant.parse("2026-01-01T12:00:00Z");
        Instant second = first.plusSeconds(1);

        // 3 pixel records at 2 unique coordinates
        pixelRepository.save(makePixel(0, 0, 1, "uuid-1", first));
        pixelRepository.save(makePixel(0, 0, 2, "uuid-2", second)); // overwrite (0,0)
        pixelRepository.save(makePixel(1, 1, 1, "uuid-1", first));

        long count = pixelRepository.countCurrentPixels();

        assertThat(count).isEqualTo(2); // 2 unique coordinates, not 3 records
    }

    @Test
    void findColorDistribution_groupsByPaletteIndex() {
        Instant now = Instant.now();

        // 2 pixels with paletteIndex=3, 1 pixel with paletteIndex=7
        pixelRepository.save(makePixel(0, 0, 3, "uuid-1", now));
        pixelRepository.save(makePixel(1, 0, 3, "uuid-1", now.plusSeconds(1)));
        pixelRepository.save(makePixel(2, 0, 7, "uuid-2", now.plusSeconds(2)));

        List<ColorCountProjection> distribution = pixelRepository.findColorDistribution();

        assertThat(distribution).hasSize(2);
        // Ordered by count descending
        assertThat(distribution.get(0).getPaletteIndex()).isEqualTo(3);
        assertThat(distribution.get(0).getPixelCount()).isEqualTo(2);
        assertThat(distribution.get(1).getPaletteIndex()).isEqualTo(7);
        assertThat(distribution.get(1).getPixelCount()).isEqualTo(1);
    }

    @Test
    void findCurrentCanvasState_erasedPixel_excluded() {
        Instant first = Instant.parse("2026-01-01T12:00:00Z");
        Instant second = Instant.parse("2026-01-01T12:00:01Z");

        // Place a colored pixel at (0,0)
        pixelRepository.save(makePixel(0, 0, 5, "uuid-1", first));
        // Erase it (paletteIndex=0 at same coordinate with later timestamp)
        pixelRepository.save(makePixel(0, 0, 0, "uuid-1", second));

        List<Pixel> result = pixelRepository.findCurrentCanvasState();

        assertThat(result).isEmpty();
    }

    @Test
    void findCurrentPixelsByAuthor_erasedPixel_excluded() {
        Instant first = Instant.parse("2026-01-01T12:00:00Z");
        Instant second = Instant.parse("2026-01-01T12:00:01Z");

        // Author places a colored pixel at (2,2)
        pixelRepository.save(makePixel(2, 2, 3, "author-erase", first));
        // Author erases it
        pixelRepository.save(makePixel(2, 2, 0, "author-erase", second));

        List<AuthorPixelProjection> result = pixelRepository.findCurrentPixelsByAuthor("author-erase");

        assertThat(result).isEmpty();
    }

    @Test
    void countCurrentPixels_excludesErasedPixels() {
        Instant first = Instant.parse("2026-01-01T12:00:00Z");
        Instant second = Instant.parse("2026-01-01T12:00:01Z");

        // Place 2 colored pixels
        pixelRepository.save(makePixel(0, 0, 5, "uuid-1", first));
        pixelRepository.save(makePixel(1, 1, 3, "uuid-1", first));
        // Erase one of them
        pixelRepository.save(makePixel(0, 0, 0, "uuid-1", second));

        long count = pixelRepository.countCurrentPixels();

        assertThat(count).isEqualTo(1);
    }

    @Test
    void findColorDistribution_excludesErasedPixels() {
        Instant first = Instant.parse("2026-01-01T12:00:00Z");
        Instant second = Instant.parse("2026-01-01T12:00:01Z");

        // Place 2 colored pixels with paletteIndex=3
        pixelRepository.save(makePixel(0, 0, 3, "uuid-1", first));
        pixelRepository.save(makePixel(1, 1, 3, "uuid-1", first));
        // Erase one location
        pixelRepository.save(makePixel(0, 0, 0, "uuid-1", second));

        List<ColorCountProjection> distribution = pixelRepository.findColorDistribution();

        // Only 1 entry for paletteIndex=3 with count 1
        assertThat(distribution).hasSize(1);
        assertThat(distribution.get(0).getPaletteIndex()).isEqualTo(3);
        assertThat(distribution.get(0).getPixelCount()).isEqualTo(1);
    }

    private Pixel makePixel(int x, int y, int paletteIndex, String authorUuid, Instant placedAt) {
        Pixel pixel = new Pixel();
        pixel.setX(x);
        pixel.setY(y);
        pixel.setPaletteIndex(paletteIndex);
        pixel.setAuthorUuid(authorUuid);
        pixel.setPlacedAt(placedAt);
        return pixel;
    }
}
