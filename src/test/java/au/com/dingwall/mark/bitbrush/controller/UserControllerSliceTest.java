package au.com.dingwall.mark.bitbrush.controller;

import au.com.dingwall.mark.bitbrush.service.PixelService;
import au.com.dingwall.mark.bitbrush.service.TurnstileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice test for UserController.
 *
 * @WebMvcTest loads ONLY UserController. PixelService is mocked.
 * Tests HTTP validation behavior (@Valid on request body) and exception paths
 * without any database interaction.
 *
 * Key learning: @Valid annotation triggers Spring's MethodArgumentNotValidException
 * BEFORE the controller method body executes. The ResponseEntityExceptionHandler
 * (parent of GlobalExceptionHandler) converts this to a 400 ProblemDetail automatically.
 * In Laravel, FormRequest validation also runs before the controller -- same concept.
 */
@WebMvcTest(UserController.class)
@ActiveProfiles("test")
class UserControllerSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PixelService pixelService;

    @MockitoBean
    private TurnstileService turnstileService;

    @BeforeEach
    void allowTurnstile() {
        when(pixelService.userExists(any())).thenReturn(false);
        when(turnstileService.verifyAndRemember(any(), any())).thenReturn(true);
    }

    @Test
    void postUsers_validRequest_returns201() throws Exception {
        // Laravel: $this->postJson('/api/users', [...])->assertStatus(201)
        doNothing().when(pixelService).registerUser(any());

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"uuid": "uuid-valid", "username": "validuser"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void postUsers_blankUsername_returns400() throws Exception {
        // @NotBlank validation fires before controller body -- like Laravel FormRequest
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"uuid": "uuid-blank", "username": ""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postUsers_existingUser_skipsTurnstile() throws Exception {
        when(pixelService.userExists("uuid-existing")).thenReturn(true);
        doNothing().when(pixelService).registerUser(any());

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"uuid": "uuid-existing", "username": "existinguser"}
                                """))
                .andExpect(status().isCreated());

        verify(turnstileService, never()).verifyAndRemember(any(), any());
        verify(turnstileService).markVerified("uuid-existing");
    }

    @Test
    void postUsers_newUserFailsTurnstile_returns403() throws Exception {
        when(pixelService.userExists("uuid-new")).thenReturn(false);
        when(turnstileService.verifyAndRemember(any(), any())).thenReturn(false);

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Turnstile-Token", "bad-token")
                        .content("""
                                {"uuid": "uuid-new", "username": "newuser"}
                                """))
                .andExpect(status().isForbidden());

        verify(pixelService, never()).registerUser(any());
    }

    @Test
    void postUsers_reservedNameYou_returns400() throws Exception {
        // Business rule check in service, not @Valid -- caught by GlobalExceptionHandler
        doThrow(new IllegalArgumentException("Username 'You' is reserved"))
                .when(pixelService).registerUser(any());

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"uuid": "uuid-you", "username": "You"}
                                """))
                .andExpect(status().isBadRequest());
    }
}
