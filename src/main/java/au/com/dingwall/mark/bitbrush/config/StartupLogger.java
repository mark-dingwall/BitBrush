package au.com.dingwall.mark.bitbrush.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class StartupLogger {

    private static final Logger log = LoggerFactory.getLogger(StartupLogger.class);

    private final BitbrushProperties properties;
    private final List<String> colorPalette;

    public StartupLogger(BitbrushProperties properties, List<String> colorPalette) {
        this.properties = properties;
        this.colorPalette = colorPalette;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logStartup() {
        log.info("BitBrush started with canvas {}x{}",
            properties.canvas().width(),
            properties.canvas().height());
        log.info("Placement config: earn 1 point every {}s, max banked: {}",
            properties.placement().earnRateSeconds(),
            properties.placement().maxBanked());
        log.info("Color palette loaded: {} colors available",
            colorPalette.size());
        log.debug("Active palette colors: {}", colorPalette);
    }
}
