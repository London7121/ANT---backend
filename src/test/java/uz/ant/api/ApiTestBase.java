package uz.ant.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.ant.api.dev.EmbeddedPostgresConfig;

/** Haqiqiy HTTP server + haqiqiy PostgreSQL (o'rnatilgan) bilan integratsiya testlari uchun umumiy asos. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"ant.rate-limit-enabled=false", "ant.admin-password=test-pass-123"})
@Import(EmbeddedPostgresConfig.class)
public abstract class ApiTestBase {
    protected static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    protected int port;

    @Autowired
    protected tools.jackson.databind.json.JsonMapper mapper;

    protected static String freshDataDir() {
        try {
            return Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "ant-test-").toString();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Res(int status, JsonNode body) {
        public String error() { return body.path("error").asString(null); }
    }

    protected Res call(String method, String path, String token, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json");
            if (token != null) b.header("Authorization", "Bearer " + token);
            HttpRequest.BodyPublisher pub = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
            HttpResponse<String> r = HTTP.send(b.method(method, pub).build(), HttpResponse.BodyHandlers.ofString());
            return new Res(r.statusCode(), r.body().isEmpty() ? JSON.createObjectNode() : JSON.readTree(r.body()));
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    protected Res get(String path, String token) { return call("GET", path, token, null); }
    protected Res post(String path, String token, Object body) { return call("POST", path, token, body); }

    protected String login(String user, String password) {
        Res r = post("/api/admin/login", null, java.util.Map.of("username", user, "password", password));
        assertThat(r.status()).as("login " + user).isEqualTo(200);
        return r.body().get("token").asString();
    }
}
