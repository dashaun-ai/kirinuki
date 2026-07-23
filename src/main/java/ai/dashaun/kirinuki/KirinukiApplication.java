package ai.dashaun.kirinuki;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class KirinukiApplication {

    public static void main(String[] args) {
        SpringApplication.run(KirinukiApplication.class, args);
    }
}
