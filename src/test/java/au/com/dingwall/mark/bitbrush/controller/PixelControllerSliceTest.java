package au.com.dingwall.mark.bitbrush.controller;

import au.com.dingwall.mark.bitbrush.dto.PixelInfoResponse;
import au.com.dingwall.mark.bitbrush.exception.InsufficientBalanceException;
import au.com.dingwall.mark.bitbrush.exception.UserNotFoundException;
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

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice test for PixelController.
 *
 * @WebMvcTest loads ONLY the web layer: @Controller, @ControllerAdvice, MockMvc.
 * PixelService is mocked with @MockitoBean -- no database, no BankingService, no WebSocket.
 *
 * Laravel equivalent: Like a Laravel Feature test with $this->mock(PixelService::class),
 * except Spring's @WebMvcTest doesn't boot services at all -- they MUST be mocked.
 * In Laravel, Feature tests boot everything; here, @WebMvcTest boots ONLY controllers.
 *
 * Compare with PixelControllerTest.java (same package): that test uses @SpringBootTest
 * which boots the entire application including database, services, and WebSocket.
 * This test runs in ~200ms; PixelControllerTest runs in ~2s.
 */
@WebMvcTest(PixelController.class)
@ActiveProfiles("test")
class PixelControllerSliceTest {

    @Autowired
    private MockMvc mockMvc;  // Laravel: $this->getJson(), $this->postJson()

    @MockitoBean
    private PixelService pixelService;  // Laravel: Mockery::mock() + $this->app->instance()

    @MockitoBean
    private TurnstileService turnstileService;

    @BeforeEach
    void allowTurnstile() {
        when(turnstileService.verify(any())).thenReturn(true);
    }

    @Test
    void postPixels_validRequest_returns200() throws Exception {
        doNothing().when(pixelService).placePixels(any());

        mockMvc.perform(post("/api/pixels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pixels": [{"x": 10, "y": 20}],
                                  "paletteIndex": 42,
                                  "authorUuid": "test-uuid"
                                }
                                """))
                .andExpect(status().isOk());

        verify(pixelService).placePixels(any());
    }

    @Test
    void postPixels_serviceThrowsIllegalArgument_returns400() throws Exception {
        doThrow(new IllegalArgumentException("paletteIndex out of range"))
                .when(pixelService).placePixels(any());

        // GlobalExceptionHandler (@ControllerAdvice) is auto-loaded by @WebMvcTest
        mockMvc.perform(post("/api/pixels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pixels": [{"x": 10, "y": 20}],
                                  "paletteIndex": 42,
                                  "authorUuid": "test-uuid"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postPixels_serviceThrowsUserNotFound_returns404() throws Exception {
        doThrow(new UserNotFoundException("unknown"))
                .when(pixelService).placePixels(any());

        mockMvc.perform(post("/api/pixels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pixels": [{"x": 10, "y": 20}],
                                  "paletteIndex": 42,
                                  "authorUuid": "unknown"
                                }
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void postPixels_serviceThrowsInsufficientBalance_returns402() throws Exception {
        doThrow(new InsufficientBalanceException(3))
                .when(pixelService).placePixels(any());

        // Verifies the full exception handler chain without starting a real BankingService
        mockMvc.perform(post("/api/pixels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pixels": [{"x": 10, "y": 20}],
                                  "paletteIndex": 42,
                                  "authorUuid": "test-uuid"
                                }
                                """))
                .andExpect(status().is(402));
    }

    @Test
    void getPixelInfo_outOfBoundsCoordinates_returns400() throws Exception {
        mockMvc.perform(get("/api/pixels/250/250/info"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getPixelInfo_returnsOkWithData() throws Exception {
        when(pixelService.getPixelInfo(5, 7)).thenReturn(
                new PixelInfoResponse(
                        "uuid-1",
                        "testuser",
                        Instant.now(),
                        List.of(new PixelInfoResponse.AuthorPixelCoordinate(5, 7))));

        mockMvc.perform(get("/api/pixels/5/7/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("testuser"))
                .andExpect(jsonPath("$.authorPixels").isArray());
    }
}
