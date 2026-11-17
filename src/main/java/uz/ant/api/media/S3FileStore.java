package uz.ant.api.media;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import uz.ant.api.config.AntProperties;

/** S3 mos ombor: AWS S3, Cloudflare R2, Supabase Storage. Fayllar o'zgarmas nom bilan, uzoq keshlanadigan qilib yuklanadi. */
@Component
@ConditionalOnProperty(prefix = "ant.storage", name = "type", havingValue = "s3")
public class S3FileStore implements FileStore {
    private final S3Client s3;
    private final String bucket;
    private final String publicUrl;

    public S3FileStore(AntProperties props) {
        var c = props.storage();
        var missing = new java.util.ArrayList<String>();
        if (blank(c.bucket())) missing.add("S3_BUCKET");
        if (blank(c.accessKey())) missing.add("S3_ACCESS_KEY");
        if (blank(c.secretKey())) missing.add("S3_SECRET_KEY");
        if (blank(c.publicUrl())) missing.add("S3_PUBLIC_URL");
        if (!missing.isEmpty())
            throw new IllegalStateException("STORAGE_TYPE=s3 uchun bo'sh yoki berilmagan o'zgaruvchilar: " + String.join(", ", missing));
        this.bucket = c.bucket();
        this.publicUrl = c.publicUrl().replaceAll("/+$", "");
        var b = S3Client.builder()
                .region(Region.of(blank(c.region()) ? "auto" : c.region()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(c.accessKey(), c.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        if (!blank(c.endpoint())) b.endpointOverride(URI.create(c.endpoint()));
        this.s3 = b.build();
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    @Override
    public void put(String name, Path file, String contentType) throws IOException {
        try {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(name).contentType(contentType)
                    .cacheControl("public, max-age=31536000, immutable").build(), RequestBody.fromFile(file));
        } catch (RuntimeException e) {
            throw new IOException("Bulutga yuklab bo'lmadi: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String name) throws IOException {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(name).build());
        } catch (RuntimeException e) {
            throw new IOException("Bulutdan o'chirib bo'lmadi: " + e.getMessage(), e);
        }
    }

    @Override
    public String url(String name) { return publicUrl + "/" + name; }
}
