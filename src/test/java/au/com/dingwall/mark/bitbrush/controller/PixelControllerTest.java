package au.com.dingwall.mark.bitbrush.controller;

import au.com.dingwall.mark.bitbrush.repository.PixelRepository;
import au.com.dingwall.mark.bitbrush.repository.UserRepository;
import au.com.dingwall.mark.bitbrush.service.BankingService;
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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for POST /api/pixels (PIXL-01, PIXL-05, CANV-02, IDEN-02, ARCH-01).
 *
 * Not using @Transactional at class level — @BeforeEach registers a user via HTTP
 * (a separate transaction) that must be visible to subsequent pixel placement requests.
 * Cleanup is handled via @AfterEach.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PixelControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PixelRepository pixelRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BankingService bankingService;

    @MockitoBean
    private TurnstileService turnstileService;

    private static final String TEST_UUID = "test-uuid-pixel-ctrl";
    private static final String TEST_USERNAME = "pixeltester";
    private static final String TEST_SESSION = "test-session-ctrl";

    @BeforeEach
    void registerTestUser() throws Exception {
        when(turnstileService.verify(any())).thenReturn(true);
        mockMvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"uuid": "%s", "username": "%s"}
                        """.formatted(TEST_UUID, TEST_USERNAME)))
                .andExpect(status().isCreated());
        bankingService.onUserConnect(TEST_UUID, TEST_SESSION);
    }

    @AfterEach
    void cleanUp() {
        bankingService.onUserDisconnect(TEST_UUID);
        pixelRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void postPixelsReturns200ForValidRequest() throws Exception {
        mockMvc.perform(post("/api/pixels")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "pixels": [{"x": 10, "y": 20}],
                          "paletteIndex": 42,
                          "authorUuid": "%s"
                        }
                        """.formatted(TEST_UUID)))
                .andExpect(status().isOk());
    }

    @Test
    void postPixelsReturns400ForOutOfBoundsCoordinates() throws Exception {
        mockMvc.perform(post("/api/pixels")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "pixels": [{"x": 250, "y": 0}],
                          "paletteIndex": 0,
                          "authorUuid": "%s"
                        }
                        """.formatted(TEST_UUID)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postPixelsReturns400ForInvalidPaletteIndex() throws Exception {
        mockMvc.perform(post("/api/pixels")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "pixels": [{"x": 0, "y": 0}],
                          "paletteIndex": 216,
                          "authorUuid": "%s"
                        }
                        """.formatted(TEST_UUID)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postPixelsReturns400ForMissingAuthorUuid() throws Exception {
        mockMvc.perform(post("/api/pixels")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "pixels": [{"x": 0, "y": 0}],
                          "paletteIndex": 0
                        }
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postPixelsReturns404ForUnknownAuthorUuid() throws Exception {
        mockMvc.perform(post("/api/pixels")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "pixels": [{"x": 0, "y": 0}],
                          "paletteIndex": 0,
                          "authorUuid": "unknown-uuid-does-not-exist"
                        }
                        """))
                .andExpect(status().isNotFound());
    }

    @Test
    void noDeleteEndpointExists() throws Exception {
        mockMvc.perform(delete("/api/pixels"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void getPixelInfo_returnsOkWithAuthorInfo() throws Exception {
        // Given: place a pixel via POST /api/pixels
        mockMvc.perform(post("/api/pixels")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "pixels": [{"x": 5, "y": 7}],
                          "paletteIndex": 10,
                          "authorUuid": "%s"
                        }
                        """.formatted(TEST_UUID)))
                .andExpect(status().isOk());

        // When/Then: GET /api/pixels/5/7/info returns author info
        mockMvc.perform(get("/api/pixels/5/7/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorUuid").value(TEST_UUID))
                .andExpect(jsonPath("$.username").value(TEST_USERNAME))
                .andExpect(jsonPath("$.placedAt").isNotEmpty())
                .andExpect(jsonPath("$.authorPixels").isArray())
                .andExpect(jsonPath("$.authorPixels.length()").value(1))
                .andExpect(jsonPath("$.authorPixels[0].x").value(5))
                .andExpect(jsonPath("$.authorPixels[0].y").value(7));
    }

    @Test
    void getPixelInfo_returns404ForEmptyCoordinate() throws Exception {
        // Given: no pixel at (249, 249) — valid coordinates with no pixel placed
        // When/Then
        mockMvc.perform(get("/api/pixels/249/249/info"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getPixelInfo_afterErase_returns404() throws Exception {
        // Use isolated UUID to avoid depleting shared TEST_UUID balance
        String eraserUuid = "test-uuid-eraser-info";
        String eraserSession = "test-session-eraser-info";
        mockMvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"uuid": "%s", "username": "erasertester1"}
                        """.formatted(eraserUuid)))
                .andExpect(status().isCreated());
        bankingService.onUserConnect(eraserUuid, eraserSession);

        try {
            // Given: place a colored pixel at (3, 3) with paletteIndex=10
            mockMvc.perform(post("/api/pixels")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {
                              "pixels": [{"x": 3, "y": 3}],
                              "paletteIndex": 10,
                              "authorUuid": "%s"
                            }
                            """.formatted(eraserUuid)))
                    .andExpect(status().isOk());

            // Verify pixel info exists before erasing
            mockMvc.perform(get("/api/pixels/3/3/info"))
                    .andExpect(status().isOk());

            // When: erase the pixel by placing paletteIndex=0 at (3, 3)
            mockMvc.perform(post("/api/pixels")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {
                              "pixels": [{"x": 3, "y": 3}],
                              "paletteIndex": 0,
                              "authorUuid": "%s"
                            }
                            """.formatted(eraserUuid)))
                    .andExpect(status().isOk());

            // Then: pixel info should return 404 (erased = logically empty)
            mockMvc.perform(get("/api/pixels/3/3/info"))
                    .andExpect(status().isNotFound());
        } finally {
            bankingService.onUserDisconnect(eraserUuid);
        }
    }

    @Test
    void getCanvas_erasedPixel_excluded() throws Exception {
        // Use isolated UUID to avoid depleting shared TEST_UUID balance
        String eraserUuid = "test-uuid-eraser-canvas";
        String eraserSession = "test-session-eraser-canvas";
        mockMvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"uuid": "%s", "username": "erasertester2"}
                        """.formatted(eraserUuid)))
                .andExpect(status().isCreated());
        bankingService.onUserConnect(eraserUuid, eraserSession);

        try {
            // Given: place a pixel at (7, 7) then erase it
            mockMvc.perform(post("/api/pixels")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {
                              "pixels": [{"x": 7, "y": 7}],
                              "paletteIndex": 42,
                              "authorUuid": "%s"
                            }
                            """.formatted(eraserUuid)))
                    .andExpect(status().isOk());

            // Erase it
            mockMvc.perform(post("/api/pixels")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {
                              "pixels": [{"x": 7, "y": 7}],
                              "paletteIndex": 0,
                              "authorUuid": "%s"
                            }
                            """.formatted(eraserUuid)))
                    .andExpect(status().isOk());

            // Then: canvas state should not contain the erased coordinate
            mockMvc.perform(get("/api/canvas"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.x == 7 && @.y == 7)]").doesNotExist());
        } finally {
            bankingService.onUserDisconnect(eraserUuid);
        }
    }

    @Test
    void postPixelsReturns402WhenBalanceZero() throws Exception {
        // Use a fresh UUID isolated to this test so balance always starts at 5,
        // regardless of what other tests may have deducted from TEST_UUID.
        String rate402Uuid = "test-uuid-402-isolated";
        String rate402Session = "test-session-402";

        // Register user and connect to banking
        mockMvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"uuid": "%s", "username": "rate402tester"}
                        """.formatted(rate402Uuid)))
                .andExpect(status().isCreated());
        bankingService.onUserConnect(rate402Uuid, rate402Session);

        try {
            // Spend all 5 starting points
            for (int i = 0; i < 5; i++) {
                mockMvc.perform(post("/api/pixels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pixels": [{"x": %d, "y": 0}],
                                  "paletteIndex": 0,
                                  "authorUuid": "%s"
                                }
                                """.formatted(i, rate402Uuid)))
                        .andExpect(status().isOk());
            }
            // 6th request — balance is 0
            mockMvc.perform(post("/api/pixels")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {
                              "pixels": [{"x": 10, "y": 10}],
                              "paletteIndex": 0,
                              "authorUuid": "%s"
                            }
                            """.formatted(rate402Uuid)))
                    .andExpect(status().is(402))
                    .andExpect(result -> {
                        String body = result.getResponse().getContentAsString();
                        assertTrue(body.contains("retryAfterSeconds"),
                            "Response body must contain retryAfterSeconds but was: " + body);
                    });
        } finally {
            bankingService.onUserDisconnect(rate402Uuid);
        }
    }

    @Test
    void getPixelInfoReturns400ForNegativeCoordinates() throws Exception {
        mockMvc.perform(get("/api/pixels/-1/-1/info"))
                .andExpect(status().isBadRequest());
    }
}
