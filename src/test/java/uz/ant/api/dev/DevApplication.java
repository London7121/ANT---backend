package uz.ant.api.dev;

import org.springframework.boot.SpringApplication;
import uz.ant.api.AntApplication;

/**
 * IntelliJ IDEA'da shu klassni ishga tushiring (Run 'DevApplication').
 * PostgreSQL o'zi ko'tariladi, API http://localhost:3001 da ishlaydi.
 * Konsolda: ./mvnw spring-boot:test-run
 */
public class DevApplication {
    public static void main(String[] args) {
        SpringApplication.from(AntApplication::main).with(EmbeddedPostgresConfig.class).run(args);
    }
}
