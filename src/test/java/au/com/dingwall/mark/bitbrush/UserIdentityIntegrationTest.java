package au.com.dingwall.mark.bitbrush;

import au.com.dingwall.mark.bitbrush.dto.*;
import au.com.dingwall.mark.bitbrush.repository.PixelRepository;
import au.com.dingwall.mark.bitbrush.repository.UserRepository;
import au.com.dingwall.mark.bitbrush.service.BankingService;
import au.com.dingwall.mark.bitbrush.service.PinCredentialService;
import au.com.dingwall.mark.bitbrush.service.TurnstileService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static au.com.dingwall.mark.bitbrush.VerificationCacheTestSupport.clearVerification;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Whole identity workflows using real persistence, Argon2, verification cache and broker messages. */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class UserIdentityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired PixelRepository pixels;
    @Autowired BankingService bank;
    @Autowired PinCredentialService credentials;
    @Autowired @Qualifier("brokerChannel") AbstractSubscribableChannel broker;
    @MockitoSpyBean TurnstileService turnstile;

    private final LinkedBlockingQueue<Message<?>> broadcasts = new LinkedBlockingQueue<>();
    private final AtomicInteger challenges = new AtomicInteger();
    private String privateId;
    private boolean challengeAccepted;
    private final ChannelInterceptor capture = new ChannelInterceptor() {
        @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
            if ("/topic/pixels".equals(SimpMessageHeaderAccessor.getDestination(message.getHeaders()))) {
                broadcasts.add(message);
            }
            return message;
        }
    };

    @BeforeEach
    void setUp() {
        privateId = UUID.randomUUID().toString();
        broker.addInterceptor(capture);
        // Only the external challenge request is replaced; the admission cache stays real.
        doAnswer(call -> {
            challenges.incrementAndGet();
            assertEquals("integration-challenge", call.getArgument(0), "Unexpected challenge token");
            assertFalse(turnstile.isVerified(privateId), "Verification preceded the challenge");
            return challengeAccepted;
        }).when(turnstile).verify(any());
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                "Verification was granted before the identity transaction committed");
            var persisted = users.findById(privateId).orElseThrow();
            assertTrue(credentials.verify(credentials.canonicalize("éx😀!"), persisted.getPinHash()),
                "Verification was granted without persisted credentials");
            return call.callRealMethod();
        }).when(turnstile).markVerified(any());
    }

    @AfterEach
    void cleanUp() {
        broker.removeInterceptor(capture);
        pixels.deleteAll();
        users.deleteAll();
        clearVerification(turnstile);
    }

    @Test
    void createThenReconnectPreservesAuthoritativeIdentityAndSpentBank() throws Exception {
        // Catches early admission, missing persisted credentials, stale-name echoing and bank reset.
        create();
        bank.ensureBank(privateId);
        bank.deductPoint(privateId);
        clearVerification(turnstile);
        challengeAccepted = false;

        JsonNode reconnected = identity("/api/users/reconnect",
            java.util.Map.of("uuid", privateId, "username", "StaleBrowserName"), 200);

        assertIdentity(reconnected);
        assertTrue(turnstile.isVerified(privateId), "Reconnect did not restore admission");
        assertEquals(2, challenges.get(), "Reconnect must use bearer authentication");
        bank.ensureBank(privateId);
        assertEquals(4, bank.getInitialState(privateId).balance(), "Reconnect reset the existing bank");
    }

    @Test
    void createThenRecoverPlacesWithTheSameBankAndOnlyPublicAuthorship() throws Exception {
        // Catches failed-challenge admission, broken recovery, UUID disclosure and bank replacement.
        create();
        bank.ensureBank(privateId);
        bank.deductPoint(privateId);
        clearVerification(turnstile);
        challengeAccepted = false;
        identity("/api/users/recover", new UserRecoveryRequest("SequenceArtist", "éx😀!"), 403);
        assertFalse(turnstile.isVerified(privateId), "A failed challenge granted admission");
        challengeAccepted = true;
        identity("/api/users/recover", new UserRecoveryRequest("SequenceArtist", "nope"), 401);
        assertFalse(turnstile.isVerified(privateId), "A wrong PIN granted admission");
        JsonNode recovered = identity("/api/users/recover", new UserRecoveryRequest("SequenceArtist", "éx😀!"), 200);
        assertIdentity(recovered);
        assertTrue(turnstile.isVerified(privateId), "Recovery did not grant admission");
        bank.ensureBank(privateId);
        assertEquals(4, bank.getInitialState(privateId).balance(), "Recovery reset the existing bank");

        var placed = mvc.perform(post("/api/pixels").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(new PixelPlacementRequest(List.of(new PixelCoordinate(12, 34)), 7, privateId))))
            .andReturn().getResponse();
        assertEquals(201, placed.getStatus());
        assertEquals(5, challenges.get(), "Verified placement unexpectedly challenged again");
        assertEquals(3, bank.getInitialState(privateId).balance());
        var row = users.findById(privateId).orElseThrow();
        assertEquals(1, pixels.count());
        assertEquals(row.getAuthorId(), pixels.findAll().getFirst().getAuthorId());

        var info = mvc.perform(get("/api/pixels/12/34/info")).andReturn().getResponse();
        assertEquals(200, info.getStatus());
        JsonNode publicInfo = mapper.readTree(info.getContentAsByteArray());
        assertPublic(publicInfo, row.getAuthorId());
        assertEquals("SequenceArtist", publicInfo.path("username").asText());
        assertEquals(1, publicInfo.path("authorPixels").size());
        assertEquals(12, publicInfo.path("authorPixels").get(0).path("x").asInt());
        assertEquals(34, publicInfo.path("authorPixels").get(0).path("y").asInt());

        Message<?> broadcast = broadcasts.poll(5, TimeUnit.SECONDS);
        assertNotNull(broadcast, "Placement was not broadcast");
        Object payload = broadcast.getPayload();
        JsonNode publicBroadcast = payload instanceof byte[] bytes ? mapper.readTree(bytes) : mapper.valueToTree(payload);
        assertPublic(publicBroadcast, row.getAuthorId());
        assertEquals(12, publicBroadcast.path("x").asInt());
        assertEquals(34, publicBroadcast.path("y").asInt());
        assertFalse(publicBroadcast.path("erased").asBoolean());
    }

    private void create() throws Exception {
        var request = new UserCreateRequest(privateId, "SequenceArtist", "e\u0301x😀!", "éx😀!");
        identity("/api/users", request, 403);
        assertEquals(0, users.count(), "A failed challenge persisted an identity");
        assertFalse(turnstile.isVerified(privateId), "A failed challenge granted admission");
        challengeAccepted = true;
        assertIdentity(identity("/api/users", request, 201));
        assertTrue(turnstile.isVerified(privateId), "Committed creation did not grant admission");
    }

    private JsonNode identity(String path, Object request, int status) throws Exception {
        var response = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
            .header("X-Turnstile-Token", "integration-challenge").content(mapper.writeValueAsBytes(request)))
            .andReturn().getResponse();
        assertEquals(status, response.getStatus());
        if (status < 300) assertEquals("no-store", response.getHeader("Cache-Control"));
        return mapper.readTree(response.getContentAsByteArray());
    }

    private void assertIdentity(JsonNode response) {
        assertEquals(2, response.size(), "Identity response exposed unexpected fields");
        assertTrue(privateId.equals(response.path("uuid").asText()), "Private identity was not preserved");
        assertEquals("SequenceArtist", response.path("username").asText());
    }

    private void assertPublic(JsonNode response, String authorId) {
        assertTrue(authorId.equals(response.path("authorId").asText()), "Public author was not preserved");
        assertFalse(response.toString().contains(privateId), "Public payload exposed a private identity");
        for (String field : List.of("uuid", "authorUuid", "pin", "pinHash", "pepper")) {
            assertFalse(response.has(field), "Public payload exposed a credential field");
        }
    }
}
