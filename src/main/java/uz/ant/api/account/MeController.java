package uz.ant.api.account;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import uz.ant.api.common.ApiException;
import uz.ant.api.common.Body;
import uz.ant.api.common.Json;
import uz.ant.api.common.Time;
import uz.ant.api.content.ContentRepository;
import uz.ant.api.security.AuthInterceptor;
import uz.ant.api.security.RateLimit;

/** Kirgan mijoz: profil, sevimlilar, o'z sharhi. */
@RestController
@RequestMapping("/api/me")
@Tag(name = "Sayt: profil", description = "Mijoz tokeni (Bearer) talab qilinadi")
@SecurityRequirement(name = "bearer")
public class MeController {
    private final ContentRepository content;
    private final SiteUserRepository users;
    private final Json json;

    public MeController(ContentRepository content, SiteUserRepository users, Json json) {
        this.content = content;
        this.users = users;
        this.json = json;
    }

    @GetMapping
    @Operation(summary = "Profil, sevimlilar va o'z sharhlari")
    public Map<String, Object> me(@RequestAttribute(AuthInterceptor.SITE_USER) SiteUser user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user", SiteAuthController.publicUser(user));
        m.put("favorites", users.favorites(user.id()));
        m.put("reviews", content.reviewsOfUser(user.id()));
        return m;
    }

    @PutMapping("/favorites")
    @Transactional
    @Operation(summary = "Sevimli mahsulotlarni saqlash", description = "Mavjud bo'lmagan mahsulot ID'lari tashlab yuboriladi.")
    public Map<String, Object> favorites(@RequestAttribute(AuthInterceptor.SITE_USER) SiteUser user, @RequestBody(required = false) JsonNode raw) {
        JsonNode ids = Body.of(raw).get("ids");
        if (ids == null || !ids.isArray()) throw ApiException.badRequest("invalid");
        Set<Long> valid = new LinkedHashSet<>();
        for (JsonNode n : ids) {
            if (!n.isIntegralNumber()) throw ApiException.badRequest("invalid");
            if (content.productExists(n.asLong())) valid.add(n.asLong());
        }
        List<Long> list = List.copyOf(valid);
        users.setFavorites(user.id(), list);
        return Map.of("favorites", list);
    }

    /** Har foydalanuvchining bitta sharhi bor: qayta yuborilsa yangilanadi; admin yashirgan bo'lsa yashirin qoladi. */
    @PutMapping("/review")
    @RateLimit(max = 20, windowSeconds = 3600)
    @Transactional
    @Operation(summary = "Sharh va baho qoldirish / yangilash", description = "rating: 1–5, text: 1–1000 belgi, lang: uz|ru|en (standart uz).")
    public ObjectNode review(@RequestAttribute(AuthInterceptor.SITE_USER) SiteUser user, @RequestBody(required = false) JsonNode raw) {
        Body b = Body.of(raw);
        JsonNode r = b.get("rating");
        if (r == null || !r.isIntegralNumber() || r.asLong() < 1 || r.asLong() > 5) throw ApiException.badRequest("invalid");
        String text = b.requireString("text").trim();
        if (text.isEmpty() || text.length() > 1000) throw ApiException.badRequest("invalid");
        String lang = b.has("lang") && b.get("lang").isString() && List.of("uz", "ru", "en").contains(b.get("lang").asString()) ? b.get("lang").asString() : "uz";

        ObjectNode loc = json.object().put("uz", "").put("ru", "").put("en", "");
        loc.put(lang, text);
        List<ObjectNode> existing = content.reviewsOfUser(user.id());
        if (!existing.isEmpty()) {
            ObjectNode review = existing.get(0);
            long id = review.get("id").asLong();
            review.put("rating", r.asInt());
            review.set("text", loc);
            return content.replace("reviews", id, review).orElseThrow();
        }
        ObjectNode doc = json.object();
        doc.put("name", user.username());
        doc.put("rating", r.asInt());
        doc.set("text", loc);
        doc.put("visible", true);
        doc.put("userId", user.id());
        doc.put("createdAt", Time.iso(Instant.now()));
        return content.insert("reviews", doc);
    }

    @DeleteMapping("/review")
    @Operation(summary = "O'z sharhini o'chirish")
    public Map<String, Object> deleteReview(@RequestAttribute(AuthInterceptor.SITE_USER) SiteUser user) {
        content.deleteReviewsOfUser(user.id());
        return Map.of("ok", true);
    }
}
