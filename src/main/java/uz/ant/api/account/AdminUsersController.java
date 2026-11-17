package uz.ant.api.account;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import tools.jackson.databind.JsonNode;
import uz.ant.api.common.ApiException;
import uz.ant.api.common.Body;
import uz.ant.api.common.Time;
import uz.ant.api.content.ContentRepository;
import uz.ant.api.security.RequirePermission;

/** Landing foydalanuvchilari: ro'yxat, bloklash, o'chirish. */
@RestController
@RequestMapping("/api/admin/users")
@SecurityRequirement(name = "bearer")
@Tag(name = "Admin: sayt foydalanuvchilari")
public class AdminUsersController {
    private final SiteUserRepository users;
    private final ContentRepository content;
    private final JdbcClient db;

    public AdminUsersController(SiteUserRepository users, ContentRepository content, JdbcClient db) {
        this.users = users;
        this.content = content;
        this.db = db;
    }

    @GetMapping
    @RequirePermission("users.view")
    @Operation(summary = "Foydalanuvchilar ro'yxati (sevimlilar va sharhlar soni bilan)")
    public List<Map<String, Object>> list() {
        Map<Long, Long> reviews = new java.util.HashMap<>();
        db.sql("SELECT (data ->> 'userId')::bigint AS uid, count(*) AS n FROM content_item WHERE collection = 'reviews' AND jsonb_typeof(data -> 'userId') = 'number' GROUP BY 1")
                .query((rs, i) -> { reviews.put(rs.getLong("uid"), rs.getLong("n")); return 0; }).list();
        Map<Long, Long> favs = new java.util.HashMap<>();
        db.sql("SELECT user_id, count(*) AS n FROM user_favorite GROUP BY 1").query((rs, i) -> { favs.put(rs.getLong("user_id"), rs.getLong("n")); return 0; }).list();
        return users.findAll().stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.id());
            m.put("username", u.username());
            m.put("createdAt", Time.iso(u.createdAt()));
            m.put("blocked", u.blocked());
            m.put("favorites", favs.getOrDefault(u.id(), 0L));
            m.put("reviews", reviews.getOrDefault(u.id(), 0L));
            return m;
        }).toList();
    }

    @PatchMapping("/{id}")
    @RequirePermission("users.edit")
    @Operation(summary = "Foydalanuvchini bloklash / blokdan chiqarish")
    public Map<String, Object> block(@PathVariable long id, @RequestBody(required = false) JsonNode body) {
        boolean blocked = Body.of(body).requireBoolean("blocked");
        if (!users.setBlocked(id, blocked)) throw ApiException.notFound();
        return Map.of("id", id, "blocked", blocked);
    }

    /** Foydalanuvchi bilan birga uning sharhlari ham o'chiriladi. */
    @DeleteMapping("/{id}")
    @RequirePermission("users.delete")
    @Transactional
    @Operation(summary = "Foydalanuvchini va uning sharhlarini o'chirish")
    public Map<String, Object> delete(@PathVariable long id) {
        content.deleteReviewsOfUser(id);
        users.delete(id);
        return Map.of("ok", true);
    }
}
