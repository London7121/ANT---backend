package uz.ant.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PasswordHasherTest {
    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void verifiesHashProducedByTheOldNodeBackend() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/legacy-fixtures.json")) {
            JsonNode v = JsonMapper.builder().build().readTree(in).get("hashVector");
            assertThat(hasher.matches(v.get("password").asString(), v.get("stored").asString())).isTrue();
            assertThat(hasher.matches("wrong", v.get("stored").asString())).isFalse();
        }
    }

    @Test
    void roundTripAndFormat() {
        String h = hasher.hash("secret-123");
        assertThat(h).matches("^[0-9a-f]{32}:[0-9a-f]{128}$"); // Node formati: salt(hex):scrypt(hex, 64 bayt)
        assertThat(hasher.matches("secret-123", h)).isTrue();
        assertThat(hasher.matches("secret-124", h)).isFalse();
        assertThat(hasher.hash("secret-123")).isNotEqualTo(h); // har safar yangi salt
    }

    @Test
    void garbageNeverMatches() {
        assertThat(hasher.matches("x", null)).isFalse();
        assertThat(hasher.matches("x", "no-colon")).isFalse();
        assertThat(hasher.matches("x", "salt:not-hex")).isFalse();
    }
}
