package uz.ant.api.content;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import uz.ant.api.common.ApiException;
import uz.ant.api.common.Body;
import uz.ant.api.common.Json;
import uz.ant.api.security.RateLimit;

/** Landing uchun ochiq API. */
@RestController
@RequestMapping("/api")
@Tag(name = "Sayt: kontent", description = "Ochiq (token talab qilinmaydi)")
public class PublicController {
    private static final Pattern PHONE = Pattern.compile("^[+\\d][\\d\\s()-]{8,}$");
    private final ContentRepository content;
    private final SettingsService settings;
    private final MessageRepository messages;
    private final Json json;

    public PublicController(ContentRepository content, SettingsService settings, MessageRepository messages, Json json) {
        this.content = content;
        this.settings = settings;
        this.messages = messages;
        this.json = json;
    }

    private List<ObjectNode> visible(String collection) {
        return content.list(collection).stream().filter(ContentRepository::visible).toList();
    }

    /** Reyting va raqamlar qo'lda emas, haqiqiy ma'lumotlardan hisoblanadi. */
    private ObjectNode liveSettings(List<ObjectNode> reviews, List<ObjectNode> products) {
        ObjectNode s = settings.settings();
        double avg = reviews.isEmpty() ? 0 : reviews.stream().mapToDouble(r -> r.path("rating").asDouble(0)).sum() / reviews.size();
        s.put("rating", Math.floor(avg * 10 + 0.5) / 10);
        s.put("reviewsCount", reviews.size());
        ObjectNode stats = s.has("stats") && s.get("stats").isObject() ? ((ObjectNode) s.get("stats")).deepCopy() : json.object();
        stats.put("clients", settings.unblockedUsers());
        stats.put("products", products.size());
        s.set("stats", stats);
        return s;
    }

    @GetMapping("/content")
    @Operation(summary = "Landing uchun barcha ochiq kontent", description = "Yashirin (visible=false) elementlarsiz; reyting va sonlar haqiqiy ma'lumotdan hisoblanadi.")
    public ResponseEntity<Map<String, Object>> content() {
        List<ObjectNode> reviews = visible("reviews").stream()
                .sorted((a, b) -> b.path("createdAt").asString("").compareTo(a.path("createdAt").asString(""))).toList();
        List<ObjectNode> products = visible("products");
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("settings", liveSettings(reviews, products));
        body.put("banners", visible("banners"));
        body.put("products", products);
        body.put("posts", visible("posts"));
        body.put("news", visible("news"));
        body.put("reviews", reviews);
        body.put("translations", settings.allTranslations());
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(body);
    }

    @PostMapping("/contact")
    @RateLimit(max = 5, windowSeconds = 3600)
    @Operation(summary = "Aloqa formasi", description = "name: majburiy, phone: kamida 9 belgi (raqam, +, bo'shliq, qavs, tire), message: ixtiyoriy.")
    public Map<String, Object> contact(@RequestBody(required = false) JsonNode raw) {
        Body b = Body.of(raw);
        String name = Body.clip(b.requireString("name").trim(), 120);
        String phone = b.requireString("phone").trim();
        if (name.isEmpty() || !PHONE.matcher(phone).matches()) throw ApiException.badRequest("invalid");
        String message = b.has("message") && b.get("message").isString() ? Body.clip(b.get("message").asString(), 3000) : "";
        messages.insert(name, Body.clip(phone, 40), message);
        return Map.of("ok", true);
    }
}
