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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for GET /api/canvas (CANV-01, ARCH-01).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CanvasControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PixelRepository pixelRepository;

    @Test
    void getCanvasReturnsEmptyArrayForBlankCanvas() throws Exception {
        mockMvc.perform(get("/api/canvas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getCanvasReturnsPlacedPixels() throws Exception {
        Pixel pixel = new Pixel();
        pixel.setX(3);
        pixel.setY(4);
        pixel.setPaletteIndex(1); // "#000033" — second palette entry (paletteIndex 0 is now eraser)
        pixel.setAuthorUuid("uuid-canvas-test");
        pixel.setPlacedAt(Instant.now());
        pixelRepository.save(pixel);

        mockMvc.perform(get("/api/canvas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].x").value(3))
                .andExpect(jsonPath("$[0].y").value(4))
                .andExpect(jsonPath("$[0].color").value("#000033"));
    }

    @Test
    void exportCanvasPng_returnsImagePngContentType() throws Exception {
        // Given: 1 pixel on the canvas
        Pixel pixel = new Pixel();
        pixel.setX(10);
        pixel.setY(10);
        pixel.setPaletteIndex(5);
        pixel.setAuthorUuid("uuid-png-test");
        pixel.setPlacedAt(Instant.now());
        pixelRepository.save(pixel);

        // When/Then
        mockMvc.perform(get("/api/canvas/png"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"bitbrush-canvas.png\""))
                .andExpect(result -> {
                    byte[] body = result.getResponse().getContentAsByteArray();
                    assertTrue(body.length > 0, "PNG response body must be non-empty");
                });
    }
}
