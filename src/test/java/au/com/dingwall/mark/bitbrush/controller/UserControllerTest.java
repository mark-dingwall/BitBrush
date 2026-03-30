package au.com.dingwall.mark.bitbrush.controller;

import au.com.dingwall.mark.bitbrush.repository.UserRepository;
import au.com.dingwall.mark.bitbrush.service.TurnstileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for POST /api/users (IDEN-01, IDEN-02).
 *
 * Not using @Transactional at class level — the duplicate username test requires
 * real separate transactions so the unique constraint is enforced between requests.
 * Cleanup is handled via @AfterEach.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private TurnstileService turnstileService;

    @BeforeEach
    void allowTurnstile() {
        when(turnstileService.verifyAndRemember(any(), any())).thenReturn(true);
    }

    @AfterEach
    void cleanUp() {
        userRepository.deleteAll();
    }

    @Test
    void postUsersReturns201ForValidRegistration() throws Exception {
        mockMvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"uuid": "uuid-valid-user", "username": "validuser"}
                        """))
                .andExpect(status().isCreated());
    }

    @Test
    void postUsersReturns409ForDuplicateUsername() throws Exception {
        // Register first user
        mockMvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"uuid": "uuid-first-user", "username": "sharedname"}
                        """))
                .andExpect(status().isCreated());

        // Attempt to register second user with same username
        mockMvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"uuid": "uuid-second-user", "username": "sharedname"}
                        """))
                .andExpect(status().isConflict());
    }

    @Test
    void postUsersReturns400ForBlankUsername() throws Exception {
        mockMvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"uuid": "uuid-blank-test", "username": ""}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postUsersReturns400ForReservedUsernameYou() throws Exception {
        mockMvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"uuid": "uuid-you-test", "username": "You"}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postUsersReturns201ForReregistrationWithoutTurnstile() throws Exception {
        // First registration — Turnstile is mocked to pass
        mockMvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"uuid": "uuid-reregister", "username": "returninguser"}
                        """))
                .andExpect(status().isCreated());

        // Reset Turnstile mock — verifyAndRemember now returns false (token rejected)
        reset(turnstileService);
        when(turnstileService.verifyAndRemember(any(), any())).thenReturn(false);

        // Re-registration should succeed because user already exists in DB
        mockMvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"uuid": "uuid-reregister", "username": "returninguser"}
                        """))
                .andExpect(status().isCreated());
    }

    @Test
    void postUsersReturns400ForReservedUsernameYouCaseInsensitive() throws Exception {
        mockMvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"uuid": "uuid-you-lower-test", "username": "you"}
                        """))
                .andExpect(status().isBadRequest());
    }
}
