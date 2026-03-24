package au.com.dingwall.mark.bitbrush.controller;

import au.com.dingwall.mark.bitbrush.dto.StatsResponse;
import au.com.dingwall.mark.bitbrush.service.PixelService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice test for StatsController.
 *
 * @WebMvcTest loads ONLY StatsController. PixelService is mocked.
 * Verifies the JSON response shape without running any JPQL aggregate queries.
 *
 * Laravel equivalent: Like testing a Laravel controller that calls
 * DB::raw('SELECT COUNT(*)...') -- but here the database layer is completely absent.
 * The test only verifies that the controller returns the service's response as JSON.
 */
@WebMvcTest(StatsController.class)
@ActiveProfiles("test")
class StatsControllerSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PixelService pixelService;

    @Test
    void getStats_returnsOkWithStatsData() throws Exception {
        when(pixelService.getStats()).thenReturn(
                new StatsResponse(42, List.of(
                        new StatsResponse.ColorCount(1, 30),
                        new StatsResponse.ColorCount(5, 12))));

        mockMvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPixels").value(42))
                .andExpect(jsonPath("$.colorDistribution.length()").value(2))
                .andExpect(jsonPath("$.colorDistribution[0].paletteIndex").value(1));
    }

    @Test
    void getStats_emptyCanvas_returnsZero() throws Exception {
        when(pixelService.getStats()).thenReturn(
                new StatsResponse(0, List.of()));

        mockMvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPixels").value(0))
                .andExpect(jsonPath("$.colorDistribution.length()").value(0));
    }
}
