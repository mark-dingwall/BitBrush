package au.com.dingwall.mark.bitbrush.service;

import au.com.dingwall.mark.bitbrush.config.BitbrushProperties;
import au.com.dingwall.mark.bitbrush.model.Pixel;
import au.com.dingwall.mark.bitbrush.repository.PixelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CanvasExportServiceTest {

    @Mock
    PixelRepository pixelRepository;

    @Mock
    BitbrushProperties bitbrushProperties;

    @Mock
    BitbrushProperties.Canvas canvasConfig;

    private CanvasExportService exportService;
    private final List<String> palette = List.of("#000000", "#FF0000", "#00FF00");

    @BeforeEach
    void setUp() {
        when(bitbrushProperties.canvas()).thenReturn(canvasConfig);
        when(canvasConfig.width()).thenReturn(10);
        when(canvasConfig.height()).thenReturn(10);
        exportService = new CanvasExportService(pixelRepository, palette, bitbrushProperties);
    }

    private Pixel makePixel(int x, int y, int paletteIndex) {
        Pixel p = new Pixel();
        p.setX(x);
        p.setY(y);
        p.setPaletteIndex(paletteIndex);
        p.setAuthorUuid("test-uuid");
        p.setPlacedAt(Instant.now());
        return p;
    }

    @Test
    void generatePng_emptyCanvas_returnsValidPng() {
        when(pixelRepository.findCurrentCanvasState()).thenReturn(Collections.emptyList());

        byte[] png = exportService.generatePng();

        assertNotNull(png);
        assertTrue(png.length > 0);
        // PNG magic bytes
        assertEquals((byte) 0x89, png[0]);
        assertEquals((byte) 0x50, png[1]);
        assertEquals((byte) 0x4E, png[2]);
        assertEquals((byte) 0x47, png[3]);
    }

    @Test
    void generatePng_validPixels_returnsValidPng() {
        when(pixelRepository.findCurrentCanvasState()).thenReturn(
                List.of(makePixel(1, 1, 1), makePixel(5, 5, 2)));

        byte[] png = exportService.generatePng();

        assertNotNull(png);
        assertTrue(png.length > 0);
        assertEquals((byte) 0x89, png[0]);
    }

    @Test
    void generatePng_boundaryPixels_handlesEdges() {
        when(pixelRepository.findCurrentCanvasState()).thenReturn(
                List.of(makePixel(0, 0, 1), makePixel(9, 9, 2)));

        byte[] png = exportService.generatePng();

        assertNotNull(png);
        assertTrue(png.length > 0);
    }

    @Test
    void generatePng_invalidPaletteIndex_skipsPixel() {
        when(pixelRepository.findCurrentCanvasState()).thenReturn(
                List.of(makePixel(1, 1, 999)));

        byte[] png = assertDoesNotThrow(() -> exportService.generatePng());

        assertNotNull(png);
        assertEquals((byte) 0x89, png[0]);
    }

    @Test
    void generatePng_outOfBoundsCoordinates_skipsPixel() {
        when(pixelRepository.findCurrentCanvasState()).thenReturn(
                List.of(makePixel(100, 100, 1)));

        byte[] png = assertDoesNotThrow(() -> exportService.generatePng());

        assertNotNull(png);
        assertEquals((byte) 0x89, png[0]);
    }
}
