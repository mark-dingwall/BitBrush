package au.com.dingwall.mark.bitbrush;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class BitbrushApplication {

    public static void main(String[] args) {
        SpringApplication.run(BitbrushApplication.class, args);
    }
}
