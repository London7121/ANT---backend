package uz.ant.api.config;

import java.nio.file.Path;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** `ant.*` sozlamalari (application.yml). */
@ConfigurationProperties(prefix = "ant")
public record AntProperties(
        String adminUser,
        String adminPassword,
        String dataDir,
        String frontendDist,
        boolean rateLimitEnabled,
        boolean trustProxy,
        ZoneId timezone,
        String importJson,
        Storage storage) {

    /** Yuklangan fayllar ombori: `local` (data/uploads) yoki `s3` (S3 mos bulut). */
    public record Storage(String type, String bucket, String region, String endpoint, String accessKey, String secretKey, String publicUrl) {
        public boolean isS3() { return "s3".equalsIgnoreCase(type); }
    }


    public Path dataPath() { return Path.of(dataDir).toAbsolutePath().normalize(); }
    public Path uploadDir() { return dataPath().resolve("uploads"); }
    public Path frontendPath() { return Path.of(frontendDist).toAbsolutePath().normalize(); }
}
