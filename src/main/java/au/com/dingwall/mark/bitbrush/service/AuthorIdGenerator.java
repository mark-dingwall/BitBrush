package au.com.dingwall.mark.bitbrush.service;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class AuthorIdGenerator {
    private final SecureRandom random;

    public AuthorIdGenerator(SecureRandom random) {
        this.random = random;
    }

    public String generate() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return "author_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
