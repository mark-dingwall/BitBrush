package au.com.dingwall.mark.bitbrush.controller;

import au.com.dingwall.mark.bitbrush.model.Pixel;
import au.com.dingwall.mark.bitbrush.repository.PixelRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for GET /api/stats (STAT-01).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StatsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PixelRepository pixelRepository;

    @Test
    void getStats_returnsOkWithTotalAndDistribution() throws Exception {
        // Given: 3 pixels at different coordinates with 2 different paletteIndexes
        Instant now = Instant.now();
        savePixel(0, 0, 1, "uuid-stats-1", now);
        savePixel(1, 0, 1, "uuid-stats-1", now.plusSeconds(1));
        savePixel(2, 0, 5, "uuid-stats-2", now.plusSeconds(2));

        // When/Then
        mockMvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPixels").value(3))
                .andExpect(jsonPath("$.colorDistribution").isArray())
                .andExpect(jsonPath("$.colorDistribution.length()").value(2))
                // Ordered by count descending: paletteIndex=1 has 2 pixels, paletteIndex=5 has 1
                .andExpect(jsonPath("$.colorDistribution[0].paletteIndex").value(1))
                .andExpect(jsonPath("$.colorDistribution[0].pixelCount").value(2))
                .andExpect(jsonPath("$.colorDistribution[1].paletteIndex").value(5))
                .andExpect(jsonPath("$.colorDistribution[1].pixelCount").value(1));
    }

    @Test
    void getStats_emptyCanvas_returnsZeroTotal() throws Exception {
        // Given: no pixels inserted
        // When/Then
        mockMvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPixels").value(0))
                .andExpect(jsonPath("$.colorDistribution").isArray())
                .andExpect(jsonPath("$.colorDistribution.length()").value(0));
    }

    private void savePixel(int x, int y, int paletteIndex, String authorUuid, Instant placedAt) {
        Pixel pixel = new Pixel();
        pixel.setX(x);
        pixel.setY(y);
        pixel.setPaletteIndex(paletteIndex);
        pixel.setAuthorUuid(authorUuid);
        pixel.setPlacedAt(placedAt);
        pixelRepository.save(pixel);
    }
}
