package uz.ant.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** ANT API: landing sayt + admin panel backend. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AntApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(AntApplication.class);
        // `--reset-admin=...` rejimida port band bo'lmasin (server baribir ochilmaydi, dastur yopiladi)
        if (java.util.Arrays.stream(args).anyMatch(a -> a.startsWith("--reset-admin"))) app.setDefaultProperties(java.util.Map.of("server.port", "0"));
        app.run(args);
    }
}
