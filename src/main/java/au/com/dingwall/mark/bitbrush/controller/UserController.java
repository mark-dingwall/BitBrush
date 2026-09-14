package au.com.dingwall.mark.bitbrush.controller;

import au.com.dingwall.mark.bitbrush.dto.UserCreateRequest;
import au.com.dingwall.mark.bitbrush.dto.UserIdentityResponse;
import au.com.dingwall.mark.bitbrush.dto.UserReconnectRequest;
import au.com.dingwall.mark.bitbrush.dto.UserRecoveryRequest;
import au.com.dingwall.mark.bitbrush.service.ClientIpResolver;
import au.com.dingwall.mark.bitbrush.service.UserIdentityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserIdentityService identities;
    private final ClientIpResolver clientIps;

    public UserController(UserIdentityService identities, ClientIpResolver clientIps) {
        this.identities = identities;
        this.clientIps = clientIps;
    }

    @PostMapping
    public ResponseEntity<UserIdentityResponse> create(
            @Valid @RequestBody UserCreateRequest request,
            @RequestHeader(value = "X-Turnstile-Token", required = false) String token) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(identities.create(request, token));
    }

    @PostMapping("/reconnect")
    public ResponseEntity<UserIdentityResponse> reconnect(@Valid @RequestBody UserReconnectRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(identities.reconnect(request));
    }

    @PostMapping("/recover")
    public ResponseEntity<UserIdentityResponse> recover(
            @Valid @RequestBody UserRecoveryRequest request,
            @RequestHeader(value = "X-Turnstile-Token", required = false) String token,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(identities.recover(request, token, clientIps.resolve(httpRequest)));
    }
}
