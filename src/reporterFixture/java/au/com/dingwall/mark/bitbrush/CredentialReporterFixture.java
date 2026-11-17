package au.com.dingwall.mark.bitbrush;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CredentialReporterFixture {
    @Test
    void designatedFailure() throws Exception {
        String sentinel = System.getenv("REPORTER_SENTINEL");
        if (sentinel == null) throw new IllegalStateException("Fixture requires its isolated parent");
        String mode = System.getenv("REPORTER_FIXTURE_MODE");
        if ("success".equals(mode)) return;
        if ("same-test-error".equals(mode)) throw new IllegalStateException("Unrelated fixture error");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.err.println(sentinel)));
        System.out.println(sentinel);
        System.err.println(sentinel);
        try {
            assertEquals("designated mismatch", sentinel);
        } catch (AssertionFailedError expected) {
            // A private constant witness distinguishes this assertion from setup/runtime failures.
            Files.writeString(Path.of(System.getenv("REPORTER_EXPECTED_FILE")),
                "E_EXPECTED_CREDENTIAL_FAILURE", StandardOpenOption.CREATE_NEW);
            throw expected;
        }
    }
}
