package au.com.dingwall.mark.bitbrush.controller;

import au.com.dingwall.mark.bitbrush.dto.UserRegistrationRequest;
import au.com.dingwall.mark.bitbrush.service.PixelService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    public UserController(PixelService pixelService) {
        this.pixelService = pixelService;
    }

    @PostMapping("/users")
    public ResponseEntity<Void> registerUser(@Valid @RequestBody UserRegistrationRequest req) {
        log.debug("POST /api/users: uuid={}", req.uuid());
        pixelService.registerUser(req);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
