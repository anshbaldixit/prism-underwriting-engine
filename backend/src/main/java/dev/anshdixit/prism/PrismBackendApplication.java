package dev.anshdixit.prism;

import dev.anshdixit.prism.config.PrismProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(PrismProperties.class)
public class PrismBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(PrismBackendApplication.class, args);
    }
}
