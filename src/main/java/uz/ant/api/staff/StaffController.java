package uz.ant.api.staff;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import tools.jackson.databind.JsonNode;
import uz.ant.api.common.ApiException;
import uz.ant.api.common.Body;
import uz.ant.api.security.AuthInterceptor;
import uz.ant.api.security.PasswordHasher;
import uz.ant.api.security.Permissions;
import uz.ant.api.security.RequirePermission;
import uz.ant.api.security.Role;
import uz.ant.api.security.Staff;
import uz.ant.api.security.StaffUser;

/** Xodimlarni boshqarish (faqat SUPER_ADMIN — `staff` moduli ruxsati orqali). */
@RestController
@RequestMapping("/api/admin/staff")
@SecurityRequirement(name = "bearer")
@Tag(name = "Admin: xodimlar")
public class StaffController {
    private final StaffRepository repo;
    private final PasswordHasher hasher;
    private final JdbcClient db;

    public StaffController(StaffRepository repo, PasswordHasher hasher, JdbcClient db) {
        this.repo = repo;
        this.hasher = hasher;
        this.db = db;
    }

    private static Role role(JsonNode v) {
        if (v == null || !v.isString()) throw ApiException.badRequest("invalid");
        try {
            return Role.valueOf(v.asString());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid");
        }
    }

    /** Noma'lum ruxsatlar tashlab yuboriladi; massiv bo'lmasa — bo'sh ro'yxat. */
    private static List<String> permissions(JsonNode v) {
        if (v == null || !v.isArray()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (JsonNode n : v) if (n.isString() && Permissions.isPermission(n.asString())) out.add(n.asString());
        return new ArrayList<>(out);
    }

    /** Tizimda kamida bitta faol SUPER_ADMIN qolishi shartini poygasiz tekshirish uchun jadvalni qulflaydi. */
    private void lock() { db.sql("LOCK TABLE staff IN SHARE ROW EXCLUSIVE MODE").update(); }

    @GetMapping
    @RequirePermission("staff.view")
    @Operation(summary = "Xodimlar ro'yxati")
    public List<StaffUser> list() { return repo.findAll().stream().map(Staff::toUser).toList(); }

    @PostMapping
    @RequirePermission("staff.create")
    @Operation(summary = "Xodim yaratish", description = "username ≥ 3 belgi, password ≥ 6 belgi, role: SUPER_ADMIN | ADMIN | BUXGALTER | SKLAD_WORKER. Band login — 409 `username_taken`.")
    public StaffUser create(@RequestBody(required = false) JsonNode raw) {
        Body b = Body.of(raw);
        String username = b.requireString("username").trim();
        String password = b.requireString("password");
        if (username.length() < 3 || password.length() < 6) throw ApiException.badRequest("invalid");
        Role role = role(b.get("role"));
        if (repo.findByUsername(username).isPresent()) throw ApiException.conflict("username_taken");
        try {
            return repo.insert(username, b.text("name", 80), role, permissions(b.get("grant")), permissions(b.get("revoke")), true, hasher.hash(password), Instant.now()).toUser();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("username_taken");
        }
    }

    @PutMapping("/{id}")
    @RequirePermission("staff.edit")
    @Transactional
    @Operation(summary = "Xodimni tahrirlash", description = "Kamida bitta faol SUPER_ADMIN qolishi shart — aks holda 400 `last_super_admin`. Parol 6 belgidan qisqa bo'lsa e'tiborga olinmaydi.")
    public StaffUser update(@PathVariable long id, @RequestBody(required = false) JsonNode raw) {
        lock();
        Staff target = repo.findById(id).orElseThrow(ApiException::notFound);
        Body b = Body.of(raw);
        String name = b.optText("name", 80).orElse(target.name());
        Role role = b.get("role") != null ? role(b.get("role")) : target.role();
        boolean active = b.optBoolean("active").orElse(target.active());
        String password = b.optString("password").orElse(null);
        List<String> grant = b.get("grant") != null ? permissions(b.get("grant")) : target.grant();
        List<String> revoke = b.get("revoke") != null ? permissions(b.get("revoke")) : target.revoke();
        String hash = password != null && password.length() >= 6 ? hasher.hash(password) : target.hash();

        Staff next = new Staff(target.id(), target.username(), name, role, grant, revoke, active, hash, target.createdAt());
        long supers = repo.activeSuperAdmins() - (target.role() == Role.SUPER_ADMIN && target.active() ? 1 : 0) + (next.role() == Role.SUPER_ADMIN && next.active() ? 1 : 0);
        if (supers == 0) throw ApiException.badRequest("last_super_admin");
        repo.update(next);
        return next.toUser();
    }

    @DeleteMapping("/{id}")
    @RequirePermission("staff.delete")
    @Transactional
    @Operation(summary = "Xodimni o'chirish", description = "O'zini o'chirib bo'lmaydi (400 `cannot_delete_self`); oxirgi faol SUPER_ADMIN ham o'chmaydi.")
    public Map<String, Object> delete(@RequestAttribute(AuthInterceptor.STAFF) Staff me, @PathVariable long id) {
        if (id == me.id()) throw ApiException.badRequest("cannot_delete_self");
        lock();
        Staff target = repo.findById(id).orElse(null);
        if (target != null && target.role() == Role.SUPER_ADMIN && target.active() && repo.activeSuperAdmins() <= 1) throw ApiException.badRequest("last_super_admin");
        repo.delete(id);
        return Map.of("ok", true);
    }
}
