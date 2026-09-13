package au.com.dingwall.mark.bitbrush.controller;

import au.com.dingwall.mark.bitbrush.dto.*;
import au.com.dingwall.mark.bitbrush.repository.PixelRepository;
import au.com.dingwall.mark.bitbrush.repository.UserRepository;
import au.com.dingwall.mark.bitbrush.service.BankingService;
import au.com.dingwall.mark.bitbrush.service.TurnstileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired PixelRepository pixels;
    @Autowired BankingService bank;
    @MockitoSpyBean TurnstileService turnstile;
    String uuid;

    @BeforeEach
    void setUp() {
        uuid = UUID.randomUUID().toString();
        doReturn(true).when(turnstile).verify(any());
    }

    @AfterEach
    void cleanUp() {
        bank.onUserDisconnect(uuid);
        users.findAll().forEach(user -> turnstile.removeVerified(user.getUuid()));
        pixels.deleteAll();
        users.deleteAll();
    }

    @Test
    void createThenReconnectReturnsTheStoredUsernameAndRestoresVerification() throws Exception {
        create(uuid, "Artist", "A😀b!", "A😀b!");
        assertTrue(turnstile.isVerified(uuid));
        turnstile.removeVerified(uuid);
        doReturn(false).when(turnstile).verify(any());
        mvc.perform(post("/api/users/reconnect").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(new UserReconnectRequest(uuid))))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.uuid").value(uuid))
            .andExpect(jsonPath("$.username").value("Artist"));
        assertTrue(turnstile.isVerified(uuid));
        assertEquals("Artist", users.findById(uuid).orElseThrow().getUsername());
    }

    @Test
    void equivalentUnicodeConfirmationCanRecoverAndPlaceWithOnlyPublicAuthorship() throws Exception {
        create(uuid, "Artist", "e\u0301x😀!", "éx😀!");
        var user = users.findById(uuid).orElseThrow();
        assertTrue(user.getAuthorId().matches("author_[A-Za-z0-9_-]{32}"));
        turnstile.removeVerified(uuid);
        String response = mvc.perform(post("/api/users/recover").contentType(MediaType.APPLICATION_JSON)
                .header("X-Turnstile-Token", "token")
                .content(mapper.writeValueAsBytes(new UserRecoveryRequest("Artist", "éx😀!"))))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.pin").doesNotExist())
            .andExpect(jsonPath("$.pinHash").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        String recovered = mapper.readTree(response).get("uuid").asText();
        assertTrue(uuid.equals(recovered), "Recovered identity mismatch");
        assertTrue(turnstile.isVerified(recovered));
        bank.onUserConnect(recovered, "identity-integration-session");
        mvc.perform(post("/api/pixels").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(new PixelPlacementRequest(
                    java.util.List.of(new PixelCoordinate(4, 6)), 7, recovered))))
            .andExpect(status().isCreated());
        String info = mvc.perform(get("/api/pixels/4/6/info"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authorId").value(user.getAuthorId()))
            .andExpect(jsonPath("$.authorUuid").doesNotExist())
            .andExpect(jsonPath("$.username").value("Artist"))
            .andReturn().getResponse().getContentAsString();
        assertTrue(info.indexOf(uuid) < 0, "Public pixel info exposed a private identity");
        assertEquals(user.getAuthorId(), pixels.findAll().getFirst().getAuthorId());
    }

    @Test
    void creationRejectsDuplicateUsernameAndPrivateUuidWithoutChangingExistingCredentials() throws Exception {
        create(uuid, "Artist", "1234", "1234");
        String originalHash = users.findById(uuid).orElseThrow().getPinHash();
        for (UserCreateRequest duplicate : java.util.List.of(
                new UserCreateRequest(UUID.randomUUID().toString(), "Artist", "abcd", "abcd"),
                new UserCreateRequest(uuid, "Replacement", "abcd", "abcd"))) {
            mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsBytes(duplicate)))
                .andExpect(status().isConflict());
        }
        assertEquals("Artist", users.findById(uuid).orElseThrow().getUsername());
        assertTrue(originalHash.equals(users.findById(uuid).orElseThrow().getPinHash()), "Existing credential changed");
        assertEquals(1, users.count());
    }

    @Test
    void wrongAndUnknownCredentialsHaveTheSamePublicFailure() throws Exception {
        create(uuid, "Artist", "1234", "1234");
        turnstile.removeVerified(uuid);
        String wrong = failedRecovery("Artist", "abcd");
        String unknown = failedRecovery("artist", "abcd");
        assertEquals(wrong, unknown);
        assertFalse(turnstile.isVerified(uuid));
    }

    @Test
    void exactCaseUsernamesCanRemainDistinct() throws Exception {
        create(uuid, "Artist", "1234", "1234");
        create(UUID.randomUUID().toString(), "artist", "abcd", "abcd");
        assertEquals(2, users.count());
    }

    @Test
    void aLegacyPublicAuthorIdCannotBeChosenAsANewPrivateUuid() throws Exception {
        String legacyPrivateId = UUID.randomUUID().toString();
        create(legacyPrivateId, "LegacyArtist", "1234", "1234");
        var legacy = users.findById(legacyPrivateId).orElseThrow();
        legacy.setAuthorId(uuid);
        legacy.setPinBackfilled(true);
        users.saveAndFlush(legacy);
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(new UserCreateRequest(uuid, "NewArtist", "abcd", "abcd"))))
            .andExpect(status().isConflict());
        assertFalse(users.existsById(uuid));
        assertFalse(turnstile.isVerified(uuid));
        assertEquals(1, users.count());
    }

    @Test
    void pinlessCreationCannotCreateAnIdentity() throws Exception {
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(java.util.Map.of("uuid", uuid, "username", "Artist"))))
            .andExpect(status().isBadRequest());
        assertEquals(0, users.count());
        assertFalse(turnstile.isVerified(uuid));
    }

    @Test
    void malformedOrMismatchedPinsCannotCreateAnIdentity() throws Exception {
        for (UserCreateRequest invalid : java.util.List.of(
                new UserCreateRequest(uuid, "Artist", "123", "123"),
                new UserCreateRequest(uuid, "Artist", "1234", "abcd"))) {
            mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsBytes(invalid)))
                .andExpect(status().isBadRequest());
        }
        assertEquals(0, users.count());
        assertFalse(turnstile.isVerified(uuid));
    }

    private void create(String privateId, String username, String pin, String confirmation) throws Exception {
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).header("X-Turnstile-Token", "token")
                .content(mapper.writeValueAsBytes(new UserCreateRequest(privateId, username, pin, confirmation))))
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", "no-store"));
    }

    private String failedRecovery(String username, String pin) throws Exception {
        return mvc.perform(post("/api/users/recover").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(new UserRecoveryRequest(username, pin))))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.title").value("Unauthorized"))
            .andExpect(jsonPath("$.detail").value("Invalid username or PIN"))
            .andReturn().getResponse().getContentAsString();
    }
}
