package au.com.dingwall.mark.bitbrush.websocket;

import au.com.dingwall.mark.bitbrush.dto.BankStateResponse;
import au.com.dingwall.mark.bitbrush.service.BankingService;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * Delivers initial bank state to a newly connected client.
 *
 * Race condition without this: earnPoints() could fire between client connect
 * and client subscription — the push is missed. @SubscribeMapping intercepts the
 * SUBSCRIBE frame and returns current state directly to that client only.
 *
 * Same pattern as UserCountController for /app/users/count (Phase 3).
 */
@Controller
public class BankController {

    private final BankingService bankingService;

    public BankController(BankingService bankingService) {
        this.bankingService = bankingService;
    }

    /**
     * Responds to SUBSCRIBE /app/bank with current bank state for this user.
     * Response is sent directly to the subscribing client — not broadcast.
     */
    @SubscribeMapping("/bank")
    public BankStateResponse handleBankSubscribe(Principal principal) {
        String uuid = (principal != null) ? principal.getName() : null;
        return bankingService.getInitialState(uuid);
    }
}
