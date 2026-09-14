package au.com.dingwall.mark.bitbrush;

import au.com.dingwall.mark.bitbrush.service.TurnstileService;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Set;

/** Simulates a process-local cache loss without adding a production invalidation API. */
public final class VerificationCacheTestSupport {
    private VerificationCacheTestSupport() {}

    @SuppressWarnings("unchecked")
    public static void clearVerification(TurnstileService service) {
        ((Set<String>) ReflectionTestUtils.getField(service, "verifiedUuids")).clear();
    }
}
