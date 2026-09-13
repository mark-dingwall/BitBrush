package au.com.dingwall.mark.bitbrush.service;

import org.junit.jupiter.api.Test;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AuthorIdGeneratorTest {
    @Test
    void generatedIdsAreUniquePublicValuesOutsideTheUuidNamespace() {
        AuthorIdGenerator generator = new AuthorIdGenerator(new SecureRandom());
        var seen = new HashSet<String>();
        for (int i = 0; i < 10_000; i++) {
            String id = generator.generate();
            assertTrue(id.matches("author_[A-Za-z0-9_-]{32}"));
            assertTrue(seen.add(id), "Repeated public author ID");
            assertThrows(IllegalArgumentException.class, () -> UUID.fromString(id));
        }
    }
}
