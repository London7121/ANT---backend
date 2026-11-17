package uz.ant.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Eski Node `db.json` ni import qilish: ID'lar, parol xeshlari, token kaliti, qoldiq va hujjat raqamlari saqlanishi kerak.
 * Namunaviy fayl va tokenlar eski Node kodi (scrypt + HMAC) bilan yaratilgan — legacy-fixtures.json.
 */
/** Testlar bitta bazani bo'lishadi: yozuvchi test oxirida ishlaydi. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LegacyImportTest extends ApiTestBase {
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        Path data = Path.of(freshDataDir());
        Path json = data.resolve("legacy-db.json");
        try (InputStream in = LegacyImportTest.class.getResourceAsStream("/legacy-sample.json")) {
            Files.copy(in, json);
        }
        r.add("ant.data-dir", data::toString);
        r.add("ant.import-json", json::toString);
    }

    private JsonNode fixtures() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/legacy-fixtures.json")) {
            return JSON.readTree(in);
        }
    }

    @Test @Order(1)
    void oldPasswordsAndTokensKeepWorking() throws IOException {
        // eski scrypt xeshi bilan kirish
        String admin = login("admin", "admin-pass-1");
        assertThat(login("sklad", "sklad-pass-1")).isNotBlank();
        assertThat(post("/api/admin/login", null, Map.of("username", "admin", "password", "wrong")).status()).isEqualTo(401);
        assertThat(post("/api/auth/login", null, Map.of("username", "kamol", "password", "päss-уз-123")).status()).isEqualTo(200); // katta-kichik harfga e'tiborsiz

        // eski Node imzolagan tokenlar o'zgarmagan kalit bilan o'tadi
        assertThat(get("/api/admin/me", fixtures().get("staffToken").asString()).body().get("username").asString()).isEqualTo("admin");
        assertThat(get("/api/me", fixtures().get("userToken").asString()).body().at("/user/username").asString()).isEqualTo("Kamol");
        assertThat(get("/api/admin/me", admin).status()).isEqualTo(200);
    }

    @Test @Order(2)
    void dataIsImportedWithIdsAndDerivedState() throws IOException {
        String admin = fixtures().get("staffToken").asString();
        var content = get("/api/content", null).body();
        assertThat(content.at("/settings/brand/name").asString()).isEqualTo("Legacy Brand");
        assertThat(content.at("/settings/seo/siteUrl").asString()).isEqualTo("https://ant.uz"); // yetishmagan maydon standartdan to'ldirildi
        assertThat(content.get("products")).hasSize(1);                                         // visible=false yashirin
        assertThat(content.at("/products/0/id").asLong()).isEqualTo(5);
        assertThat(content.at("/translations/uz/hero.title").asString()).isEqualTo("Salom");
        assertThat(content.at("/settings/rating").asDouble()).isEqualTo(4.0);                   // (5 + 3) / 2

        var me = get("/api/me", fixtures().get("userToken").asString()).body();
        assertThat(me.get("favorites").valueStream().map(JsonNode::asLong).toList()).containsExactly(5L, 9L);
        assertThat(me.get("reviews")).hasSize(1);

        // qoldiq hujjatlardan hisoblanadi: +100 −30 −10(ko'chirildi) ; bekor qilingan kirim hisobga olinmaydi
        JsonNode item = get("/api/admin/catalog", admin).body().get(0);
        assertThat(item.get("id").asLong()).isEqualTo(11);
        assertThat(item.get("barcodes").valueStream().map(JsonNode::asString).toList()).containsExactly("4780000000011", "ABC-1234");
        assertThat(item.at("/stock/1").asDouble()).isEqualTo(60.0);
        assertThat(item.at("/stock/2").asDouble()).isEqualTo(10.0);
        assertThat(item.get("cost").asDouble()).isEqualTo(12.345);                               // 2 xonadan ortiq narx yo'qolmaydi

        var cancelled = get("/api/admin/movements?status=cancelled", admin).body();
        assertThat(cancelled.get("total").asLong()).isEqualTo(1);
        assertThat(cancelled.at("/rows/0/cancelReason").asString()).isEqualTo("xato");
        assertThat(get("/api/admin/finance/payables", admin).body().get(0).get("balance").asDouble()).isEqualTo(734.5); // 100×12.345 − 500
    }

    @Test @Order(3)
    void sequencesAndCountersContinueAfterImport() throws IOException {
        String admin = fixtures().get("staffToken").asString();
        // hujjat raqami eski hisoblagichdan davom etadi (RECEIPT: 2 → KR-00003)
        var receipt = post("/api/admin/movements", admin, Map.of("type", "RECEIPT", "warehouseId", 1, "lines", List.of(Map.of("itemId", 11, "qty", 1, "price", 1))));
        assertThat(receipt.status()).isEqualTo(200);
        assertThat(receipt.body().get("number").asString()).isEqualTo("KR-00003");
        assertThat(receipt.body().get("id").asLong()).isGreaterThan(24);
        // yangi xodim / banner ID'lari import qilinganlardan keyin keladi
        assertThat(post("/api/admin/staff", admin, Map.of("username", "newbie", "password", "newbie-pass", "role", "ADMIN")).body().get("id").asLong()).isGreaterThan(3);
        assertThat(post("/api/admin/banners", admin, Map.of("accent", "#000")).body().get("id").asLong()).isEqualTo(8);
    }
}
