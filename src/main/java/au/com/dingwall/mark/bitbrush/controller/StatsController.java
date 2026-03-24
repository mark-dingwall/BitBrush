package au.com.dingwall.mark.bitbrush.controller;

import au.com.dingwall.mark.bitbrush.dto.StatsResponse;
import au.com.dingwall.mark.bitbrush.service.PixelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves aggregate canvas statistics (STAT-01).
 */
@RestController
@RequestMapping("/api")
public class StatsController {

    private static final Logger log = LoggerFactory.getLogger(StatsController.class);

    private final PixelService pixelService;

    public StatsController(PixelService pixelService) {
        this.pixelService = pixelService;
    }

    @GetMapping("/stats")
    public ResponseEntity<StatsResponse> getStats() {
        log.debug("GET /api/stats");
        return ResponseEntity.ok(pixelService.getStats());
    }
}
