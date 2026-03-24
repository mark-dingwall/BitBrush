package au.com.dingwall.mark.bitbrush.exception;

import au.com.dingwall.mark.bitbrush.repository.PixelRepository;
import au.com.dingwall.mark.bitbrush.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for GlobalExceptionHandler ProblemDetail responses (ARCH-02).
 *
 * Not using @Transactional at class level — the duplicate username test requires
 * real separate transactions so the unique constraint is enforced between requests.
 * Cleanup is handled via @AfterEach.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PixelRepository pixelRepository;

    private static final String TEST_UUID = "test-uuid-ex-handler";
    private static final String TEST_USERNAME = "exhandler";

    @BeforeEach
    void registerTestUser() throws Exception {
        mockMvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"uuid": "%s", "username": "%s"}
                        """.formatted(TEST_UUID, TEST_USERNAME)))
                .andExpect(status().isCreated());
    }

    @AfterEach
    void cleanUp() {
        pixelRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void invalidRequestBodyReturns400WithProblemDetailFormat() throws Exception {
        mockMvc.perform(post("/api/pixels")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ malformed json "))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void missingRequiredFieldReturns400() throws Exception {
        // Missing "pixels" array
        mockMvc.perform(post("/api/pixels")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"paletteIndex": 0, "authorUuid": "%s"}
                        """.formatted(TEST_UUID)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownUserReturns404WithProblemDetailFormat() throws Exception {
        mockMvc.perform(post("/api/pixels")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "pixels": [{"x": 0, "y": 0}],
                          "paletteIndex": 0,
                          "authorUuid": "nonexistent-uuid"
                        }
                        """))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("User Not Found"));
    }

    @Test
    void duplicateUsernameReturns409() throws Exception {
        // Register first user with a unique username for this test
        mockMvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"uuid": "uuid-dup-first", "username": "dupname"}
                        """))
                .andExpect(status().isCreated());

        // Second registration with same username — triggers DataIntegrityViolationException
        mockMvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"uuid": "uuid-dup-second", "username": "dupname"}
                        """))
                .andExpect(status().isConflict());
    }
}
