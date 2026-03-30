package au.com.dingwall.mark.bitbrush.service;

import au.com.dingwall.mark.bitbrush.config.TurnstileProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TurnstileServiceTest {

    @Mock(answer = Answers.RETURNS_SELF)
    RestClient.Builder restClientBuilder;

    @Mock
    RestClient restClient;

    @Mock(answer = Answers.RETURNS_SELF)
    RestClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    RestClient.ResponseSpec responseSpec;

    private TurnstileService turnstileService;

    @BeforeEach
    void setUp() {
        when(restClientBuilder.build()).thenReturn(restClient);
        TurnstileProperties properties = new TurnstileProperties("site-key", "test-secret");
        turnstileService = new TurnstileService(properties, restClientBuilder);
    }

    private void stubVerifyResponse(TurnstileService.TurnstileResponse response) {
        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(TurnstileService.TurnstileResponse.class)).thenReturn(response);
    }

    @Test
    void verify_nullToken_returnsFalse() {
        assertFalse(turnstileService.verify(null));
    }

    @Test
    void verify_blankToken_returnsFalse() {
        assertFalse(turnstileService.verify("   "));
    }

    @Test
    void verify_successResponse_returnsTrue() {
        stubVerifyResponse(new TurnstileService.TurnstileResponse(true, null));
        assertTrue(turnstileService.verify("valid-token"));
    }

    @Test
    void verify_failedResponse_returnsFalse() {
        stubVerifyResponse(new TurnstileService.TurnstileResponse(false, java.util.List.of("invalid-input-response")));
        assertFalse(turnstileService.verify("bad-token"));
    }

    @Test
    void verify_nullResponse_returnsFalse() {
        stubVerifyResponse(null);
        assertFalse(turnstileService.verify("token-with-null-response"));
    }

    @Test
    void verify_exception_returnsFalse() {
        when(restClient.post()).thenThrow(new RuntimeException("connection refused"));
        assertFalse(turnstileService.verify("token-that-causes-error"));
    }

    @Test
    void verifyAndRemember_addsToVerifiedSet() {
        stubVerifyResponse(new TurnstileService.TurnstileResponse(true, null));

        assertFalse(turnstileService.isVerified("uuid-1"));
        assertTrue(turnstileService.verifyAndRemember("valid-token", "uuid-1"));
        assertTrue(turnstileService.isVerified("uuid-1"));

        turnstileService.removeVerified("uuid-1");
        assertFalse(turnstileService.isVerified("uuid-1"));
    }

    @Test
    void markVerified_addsToVerifiedSet() {
        assertFalse(turnstileService.isVerified("uuid-mark"));
        turnstileService.markVerified("uuid-mark");
        assertTrue(turnstileService.isVerified("uuid-mark"));
    }

    @Test
    void isVerified_nullUuid_returnsFalse() {
        assertFalse(turnstileService.isVerified(null));
    }

    @Test
    void markVerified_nullUuid_doesNotThrow() {
        assertDoesNotThrow(() -> turnstileService.markVerified(null));
    }

    @Test
    void removeVerified_nullUuid_doesNotThrow() {
        assertDoesNotThrow(() -> turnstileService.removeVerified(null));
    }
}
