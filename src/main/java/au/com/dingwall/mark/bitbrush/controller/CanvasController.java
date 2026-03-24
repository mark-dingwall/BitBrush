package au.com.dingwall.mark.bitbrush.controller;

import au.com.dingwall.mark.bitbrush.dto.CanvasPixelResponse;
import au.com.dingwall.mark.bitbrush.service.CanvasExportService;
import au.com.dingwall.mark.bitbrush.service.PixelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Serves the current state of the shared pixel canvas.
 */
@RestController
@RequestMapping("/api")
public class CanvasController {

    private static final Logger log = LoggerFactory.getLogger(CanvasController.class);

    private final PixelService pixelService;
    private final CanvasExportService canvasExportService;

    public CanvasController(PixelService pixelService, CanvasExportService canvasExportService) {
        this.pixelService = pixelService;
        this.canvasExportService = canvasExportService;
    }

    @GetMapping("/canvas")
    public ResponseEntity<List<CanvasPixelResponse>> getCanvas() {
        log.debug("GET /api/canvas");
        return ResponseEntity.ok(pixelService.getCurrentCanvasState());
    }

    @GetMapping("/canvas/png")
    public ResponseEntity<byte[]> exportCanvasPng() {
        log.debug("GET /api/canvas/png");
        byte[] pngBytes = canvasExportService.generatePng();
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bitbrush-canvas.png\"")
                .body(pngBytes);
    }
}
