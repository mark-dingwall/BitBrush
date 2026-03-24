package au.com.dingwall.mark.bitbrush.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class BitbrushPropertiesTest {

    @Autowired
    private BitbrushProperties properties;

    @Autowired
    private List<String> colorPalette;

    @Test
    void canvasPropertiesBind() {
        assertThat(properties.canvas().width()).isEqualTo(250);
        assertThat(properties.canvas().height()).isEqualTo(250);
    }

    @Test
    void placementPropertiesBind() {
        assertThat(properties.placement().earnRateSeconds()).isEqualTo(3);
        assertThat(properties.placement().maxBanked()).isEqualTo(25);
        assertThat(properties.placement().startingBalance()).isEqualTo(5);
    }

    @Test
    void colorPaletteLoads() {
        assertThat(colorPalette).isNotNull();
        assertThat(colorPalette).isNotEmpty();
        assertThat(colorPalette.size()).isGreaterThanOrEqualTo(100);
        // Verify first and last colors are black and white
        assertThat(colorPalette.getFirst()).isEqualTo("#000000");
        assertThat(colorPalette.getLast()).isEqualTo("#FFFFFF");
    }
}
