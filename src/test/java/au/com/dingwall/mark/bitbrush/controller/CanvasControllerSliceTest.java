package au.com.dingwall.mark.bitbrush.controller;

import au.com.dingwall.mark.bitbrush.dto.CanvasPixelResponse;
import au.com.dingwall.mark.bitbrush.service.CanvasExportService;
import au.com.dingwall.mark.bitbrush.service.PixelService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice test for CanvasController.
 *
 * @WebMvcTest loads ONLY CanvasController and web infrastructure.
 * Both PixelService and CanvasExportService are mocked -- no database queries.
 *
 * Demonstrates mocking multiple service dependencies in a single slice test.
 * Compare with CanvasControllerTest.java: that test uses real repositories
 * and actual H2 queries; this test verifies HTTP behavior only.
 */
@WebMvcTest(CanvasController.class)
@ActiveProfiles("test")
class CanvasControllerSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PixelService pixelService;

    @MockitoBean
    private CanvasExportService canvasExportService;

    @Test
    void getCanvas_returnsOkWithPixelList() throws Exception {
        when(pixelService.getCurrentCanvasState())
                .thenReturn(List.of(new CanvasPixelResponse(3, 4, "#FF0000")));

        mockMvc.perform(get("/api/canvas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].x").value(3))
                .andExpect(jsonPath("$[0].color").value("#FF0000"));
    }

    @Test
    void getCanvas_emptyCanvas_returnsEmptyArray() throws Exception {
        when(pixelService.getCurrentCanvasState())
                .thenReturn(List.of());

        mockMvc.perform(get("/api/canvas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void exportCanvasPng_returnsImagePng() throws Exception {
        // PNG header magic bytes
        when(canvasExportService.generatePng())
                .thenReturn(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});

        mockMvc.perform(get("/api/canvas/png"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"bitbrush-canvas.png\""));
    }
}
