package au.com.dingwall.mark.bitbrush;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CredentialReporterFixture {
    @Test
    void designatedFailure() {
        String sentinel = System.getenv("REPORTER_SENTINEL");
        if (sentinel == null) throw new IllegalStateException("Fixture requires its isolated parent");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.err.println(sentinel)));
        System.out.println(sentinel);
        System.err.println(sentinel);
        assertEquals("designated mismatch", sentinel);
    }
}
