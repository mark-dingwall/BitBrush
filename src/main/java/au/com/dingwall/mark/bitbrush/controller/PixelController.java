package au.com.dingwall.mark.bitbrush.controller;

import au.com.dingwall.mark.bitbrush.dto.PixelInfoResponse;
import au.com.dingwall.mark.bitbrush.dto.PixelPlacementRequest;
import au.com.dingwall.mark.bitbrush.exception.TurnstileException;
import au.com.dingwall.mark.bitbrush.service.PixelService;
import au.com.dingwall.mark.bitbrush.service.TurnstileService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Accepts pixel placement requests.
 * No DELETE endpoint — pixels are permanent (PIXL-05).
 */
@RestController
@RequestMapping("/api")
public class PixelController {

    private static final Logger log = LoggerFactory.getLogger(PixelController.class);

    private final PixelService pixelService;
    private final TurnstileService turnstileService;

    public PixelController(PixelService pixelService, TurnstileService turnstileService) {
        this.pixelService = pixelService;
        this.turnstileService = turnstileService;
    }

    @PostMapping("/pixels")
    public ResponseEntity<Void> placePixels(
            @Valid @RequestBody PixelPlacementRequest request,
            @RequestHeader(value = "X-Turnstile-Token", required = false) String turnstileToken) {
        log.debug("POST /api/pixels: {} pixels, paletteIndex={}", request.pixels().size(), request.paletteIndex());
        if (!turnstileService.verify(turnstileToken)) {
            throw new TurnstileException("Turnstile verification failed");
        }
        pixelService.placePixels(request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping("/pixels/{x}/{y}/info")
    public ResponseEntity<PixelInfoResponse> getPixelInfo(@PathVariable int x, @PathVariable int y) {
        log.debug("GET /api/pixels/{}/{}/info", x, y);
        if (x < 0 || x > 249 || y < 0 || y > 249) {
            throw new IllegalArgumentException(
                    "Coordinates out of bounds: x=" + x + ", y=" + y + " (valid range: 0-249)");
        }
        PixelInfoResponse info = pixelService.getPixelInfo(x, y);
        if (info == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(info);
    }
}
