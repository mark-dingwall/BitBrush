package au.com.dingwall.mark.bitbrush.controller;

import au.com.dingwall.mark.bitbrush.dto.UserRegistrationRequest;
import au.com.dingwall.mark.bitbrush.exception.TurnstileException;
import au.com.dingwall.mark.bitbrush.service.PixelService;
import au.com.dingwall.mark.bitbrush.service.TurnstileService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registers UUID-to-username mappings for pixel authorship.
 */
@RestController
@RequestMapping("/api")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final PixelService pixelService;
    private final TurnstileService turnstileService;

    public UserController(PixelService pixelService, TurnstileService turnstileService) {
        this.pixelService = pixelService;
        this.turnstileService = turnstileService;
    }

    @PostMapping("/users")
    public ResponseEntity<Void> registerUser(
            @Valid @RequestBody UserRegistrationRequest req,
            @RequestHeader(value = "X-Turnstile-Token", required = false) String turnstileToken) {
        log.debug("POST /api/users: uuid={}", req.uuid());
        if (!turnstileService.verifyAndRemember(turnstileToken, req.uuid())) {
            throw new TurnstileException("Turnstile verification failed");
        }
        pixelService.registerUser(req);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
