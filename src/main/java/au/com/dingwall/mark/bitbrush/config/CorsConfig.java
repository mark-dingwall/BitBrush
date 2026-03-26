package au.com.dingwall.mark.bitbrush.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("https://*.github.io", "https://*.fly.dev", "http://localhost:[*]")
                .allowedMethods("GET", "POST")
                .allowedHeaders("Content-Type", "X-Turnstile-Token")
                .allowCredentials(false);
    }
}
