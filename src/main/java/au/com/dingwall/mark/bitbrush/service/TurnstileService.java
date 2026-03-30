package au.com.dingwall.mark.bitbrush.service;

import au.com.dingwall.mark.bitbrush.config.TurnstileProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TurnstileService {

    private static final Logger log = LoggerFactory.getLogger(TurnstileService.class);
    private static final String VERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private final TurnstileProperties properties;
    private final RestClient restClient;
    private final Set<String> verifiedUuids = ConcurrentHashMap.newKeySet();

    public TurnstileService(TurnstileProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = restClientBuilder.requestFactory(factory).build();
    }

    /**
     * Verify a Turnstile token and mark the UUID as verified on success.
     */
    public boolean verifyAndRemember(String token, String uuid) {
        boolean result = verify(token);
        if (result && uuid != null) {
            verifiedUuids.add(uuid);
        }
        return result;
    }

    public boolean isVerified(String uuid) {
        return uuid != null && verifiedUuids.contains(uuid);
    }

    public void markVerified(String uuid) {
        if (uuid != null) {
            verifiedUuids.add(uuid);
        }
    }

    public void removeVerified(String uuid) {
        if (uuid != null) {
            verifiedUuids.remove(uuid);
        }
    }

    public boolean verify(String token) {
        if (token == null || token.isBlank()) {
            log.warn("Turnstile token is missing or blank");
            return false;
        }

        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("secret", properties.secretKey());
            form.add("response", token);

            TurnstileResponse response = restClient.post()
                    .uri(VERIFY_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TurnstileResponse.class);

            if (response == null) {
                log.warn("Turnstile verification returned null response");
                return false;
            }

            if (!response.success()) {
                log.warn("Turnstile verification failed: error-codes={}", response.errorCodes());
            }
            return response.success();
        } catch (Exception e) {
            log.error("Turnstile verification failed with exception", e);
            return false;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TurnstileResponse(
            @JsonProperty("success") boolean success,
            @JsonProperty("error-codes") List<String> errorCodes
    ) {}
}
