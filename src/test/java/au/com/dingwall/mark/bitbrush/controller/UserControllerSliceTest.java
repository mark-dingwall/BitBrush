package au.com.dingwall.mark.bitbrush.controller;

import au.com.dingwall.mark.bitbrush.dto.*;
import au.com.dingwall.mark.bitbrush.exception.*;
import au.com.dingwall.mark.bitbrush.service.ClientIpResolver;
import au.com.dingwall.mark.bitbrush.service.UserIdentityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserController.class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(
    print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles("test")
class UserControllerSliceTest {
    private static final String UUID = "681596ed-5ac6-44a4-a340-a390d2f9456c";
    private static final String PIN = "A😀b!";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean UserIdentityService identities;
    @MockitoBean ClientIpResolver clientIps;

    @ParameterizedTest
    @ValueSource(strings = {"create", "reconnect", "recover"})
    void identityContractsReturnOnlyTheAuthoritativeIdentityWithoutCaching(String operation) throws Exception {
        UserIdentityResponse identity = new UserIdentityResponse(UUID, "Artist");
        InetAddress ip = InetAddress.getByAddress(new byte[] {127, 0, 0, 7});
        String path;
        Object request;
        int status;
        switch (operation) {
            case "create" -> {
                path = "/api/users";
                request = new UserCreateRequest(UUID, "Artist", PIN, PIN);
                status = 201;
                when(identities.create((UserCreateRequest) request, "token")).thenReturn(identity);
            }
            case "reconnect" -> {
                path = "/api/users/reconnect";
                request = new UserReconnectRequest(UUID);
                status = 200;
                when(identities.reconnect((UserReconnectRequest) request)).thenReturn(identity);
            }
            default -> {
                path = "/api/users/recover";
                request = new UserRecoveryRequest("Artist", PIN);
                status = 200;
                when(clientIps.resolve(any())).thenReturn(ip);
                when(identities.recover((UserRecoveryRequest) request, "token", ip)).thenReturn(identity);
            }
        }
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).header("X-Turnstile-Token", "token")
                .content(mapper.writeValueAsBytes(request)))
            .andExpect(status().is(status))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(result -> org.junit.jupiter.api.Assertions.assertTrue(
                UUID.equals(mapper.readTree(result.getResponse().getContentAsByteArray()).path("uuid").asText()),
                "Identity response returned the wrong private identity"))
            .andExpect(jsonPath("$.username").value("Artist"))
            .andExpect(jsonPath("$.*", hasSize(2)))
            .andExpect(jsonPath("$.pin").doesNotExist())
            .andExpect(jsonPath("$.pinConfirmation").doesNotExist())
            .andExpect(jsonPath("$.pinHash").doesNotExist());
        if (operation.equals("recover")) {
            verify(clientIps).resolve(any());
            // Inspect calls without Mockito's failure renderer, which includes credential-bearing arguments.
            var recoveryCalls = mockingDetails(identities).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("recover"))
                .toList();
            assertTrue(recoveryCalls.size() == 1, "Recovery workflow must be invoked exactly once");
            var recoveryCall = recoveryCalls.getFirst();
            assertTrue(recoveryCall.getArgument(0) instanceof UserRecoveryRequest,
                "Recovery workflow received an invalid request");
            UserRecoveryRequest recoveryRequest = recoveryCall.getArgument(0);
            assertTrue("Artist".equals(recoveryRequest.username()), "Recovery workflow received the wrong username");
            assertTrue(PIN.equals(recoveryRequest.pin()), "Recovery workflow received the wrong PIN");
            assertTrue("token".equals(recoveryCall.getArgument(1)),
                "Recovery workflow received the wrong bot-verification token");
            assertTrue(ip.equals(recoveryCall.getArgument(2)), "Recovery workflow received the wrong client address");
        } else {
            verifyNoInteractions(clientIps);
        }
    }

    @Test
    void absentTurnstileHeaderIsDelegatedToTheWorkflow() throws Exception {
        when(identities.create(any(), isNull())).thenThrow(new TurnstileException("Turnstile verification failed"));
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(valid("create"))))
            .andExpect(status().isForbidden());
        verify(identities).create(any(), isNull());
    }

    @ParameterizedTest(name = "invalid identity field case {index}")
    @MethodSource("invalidFields")
    void invalidIdentityFieldsAreRejectedBeforeTheWorkflow(String operation, String field, Object value) throws Exception {
        Map<String, Object> body = valid(operation);
        body.put(field, value);
        mvc.perform(post(path(operation)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        verifyNoInteractions(identities, clientIps);
    }

    static Stream<Arguments> invalidFields() {
        var invalid = Stream.<Arguments>builder();
        for (String operation : new String[] {"create", "reconnect"}) {
            for (Object value : new Object[] {null, "", " ", "not-a-uuid", "1-1-1-1-1", UUID.toUpperCase(), "author_12345678901234567890123456789012"}) {
                invalid.add(Arguments.of(operation, "uuid", value));
            }
        }
        for (String operation : new String[] {"create", "recover"}) {
            for (Object value : new Object[] {null, "", " ", "ab", "x".repeat(31), "bad.name"}) {
                invalid.add(Arguments.of(operation, "username", value));
            }
            invalid.add(Arguments.of(operation, "pin", null));
            invalid.add(Arguments.of(operation, "pin", "x".repeat(257)));
        }
        invalid.add(Arguments.of("create", "pinConfirmation", null));
        invalid.add(Arguments.of("create", "pinConfirmation", "x".repeat(257)));
        return invalid.build();
    }

    @ParameterizedTest
    @MethodSource("malformedBodies")
    void missingOrMalformedBodiesAreRejected(String operation, String body) throws Exception {
        mvc.perform(post(path(operation)).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        verifyNoInteractions(identities, clientIps);
    }

    static Stream<Arguments> malformedBodies() {
        return Stream.of("create", "reconnect", "recover")
            .flatMap(operation -> Stream.of("", "null", "{bad", "{}")
                .map(body -> Arguments.of(operation, body)));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void workflowFailuresUseGenericProblemContracts(RuntimeException failure, int status, String title,
            String detail, String retryAfter) throws Exception {
        when(identities.recover(any(), any(), any())).thenThrow(failure);
        var result = mvc.perform(post("/api/users/recover").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(valid("recover"))))
            .andExpect(status().is(status))
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.title").value(title))
            .andExpect(jsonPath("$.detail").value(detail));
        if (retryAfter == null) {
            result.andExpect(header().doesNotExist("Retry-After"));
        } else {
            result.andExpect(header().string("Retry-After", retryAfter));
        }
    }

    static Stream<Arguments> failures() {
        return Stream.of(
            Arguments.of(new org.springframework.dao.DataIntegrityViolationException("Private database detail"), 500, "Internal Server Error", "Request could not be completed", null),
            Arguments.of(new IllegalStateException("Private transaction detail"), 500, "Internal Server Error", "Request could not be completed", null),
            Arguments.of(new DuplicateIdentityException(), 409, "Conflict", "Identity already exists", null),
            Arguments.of(new InvalidCredentialsException(), 401, "Unauthorized", "Invalid username or PIN", null),
            Arguments.of(new RecoveryThrottledException(321), 429, "Too Many Requests", "Identity recovery is temporarily unavailable", "321"),
            Arguments.of(new RecoveryCapacityException(123), 429, "Too Many Requests", "Identity recovery is temporarily unavailable", "123"),
            Arguments.of(new PinCapacityException(2), 503, "Service Unavailable", "PIN credential processing is temporarily unavailable", "2"),
            Arguments.of(new InvalidPinException(), 400, "Bad Request", "Invalid PIN", null),
            Arguments.of(new UserNotFoundException(), 404, "User Not Found", "User not found", null),
            Arguments.of(new TurnstileException("Turnstile verification failed"), 403, "Forbidden", "Turnstile verification failed", null)
        );
    }

    private static Map<String, Object> valid(String operation) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!operation.equals("recover")) result.put("uuid", UUID);
        if (!operation.equals("reconnect")) {
            result.put("username", "Artist");
            result.put("pin", PIN);
        }
        if (operation.equals("create")) result.put("pinConfirmation", PIN);
        return result;
    }

    private static String path(String operation) {
        return operation.equals("create") ? "/api/users" : "/api/users/" + operation;
    }
}
