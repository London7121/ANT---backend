package uz.ant.api.account;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
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
import uz.ant.api.security.RateLimit;
import uz.ant.api.security.Staff;
import uz.ant.api.security.StaffUser;
import uz.ant.api.security.TokenService;
import uz.ant.api.staff.StaffRepository;

/** Admin panel: kirish va o'z profili. */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin: kirish va profil")
public class AdminAccountController {
    private final StaffRepository staff;
    private final PasswordHasher hasher;
    private final TokenService tokens;

    public AdminAccountController(StaffRepository staff, PasswordHasher hasher, TokenService tokens) {
        this.staff = staff;
        this.hasher = hasher;
        this.tokens = tokens;
    }

    @PostMapping("/login")
    @RateLimit(max = 10, windowSeconds = 15 * 60)
    @Operation(summary = "Xodim kirishi", description = "Token olib, Swagger'dagi **Authorize** tugmasiga kiriting.")
    public Map<String, Object> login(@RequestBody(required = false) JsonNode raw) {
        Body b = Body.of(raw);
        String username = b.requireString("username");
        String password = b.requireString("password");
        Staff s = staff.findByUsername(username).orElse(null);
        if (s == null || !hasher.matches(password, s.hash())) throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials");
        if (!s.active()) throw ApiException.forbidden("inactive");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", tokens.issue(TokenService.Kind.STAFF, s.id()));
        m.put("user", s.toUser());
        return m;
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "Joriy xodim")
    public StaffUser me(@RequestAttribute(AuthInterceptor.STAFF) Staff me) {
        return me.toUser();
    }

    @PutMapping("/profile")
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "Ism va loginni o'zgartirish")
    public StaffUser profile(@RequestAttribute(AuthInterceptor.STAFF) Staff me, @RequestBody(required = false) JsonNode raw) {
        Body b = Body.of(raw);
        String name = b.optText("name", 80).orElse(me.name());
        String username = me.username();
        String wanted = b.optString("username").map(String::trim).orElse("");
        if (!wanted.isEmpty() && !wanted.equals(me.username())) {
            if (wanted.length() < 3 || wanted.length() > 32 || staff.usernameExists(wanted, me.id())) throw ApiException.conflict("username_invalid_or_taken");
            username = wanted;
        }
        Staff next = new Staff(me.id(), username, name, me.role(), me.grant(), me.revoke(), me.active(), me.hash(), me.createdAt());
        try {
            staff.update(next);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("username_invalid_or_taken");
        }
        return next.toUser();
    }

    @PostMapping("/password")
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "Parolni almashtirish", description = "Joriy parol noto'g'ri — 400 `wrong_password`; yangisi 6 belgidan qisqa — 400 `too_short`.")
    public Map<String, Object> password(@RequestAttribute(AuthInterceptor.STAFF) Staff me, @RequestBody(required = false) JsonNode raw) {
        Body b = Body.of(raw);
        String current = b.requireString("current");
        String next = b.requireString("next");
        if (!hasher.matches(current, me.hash())) throw ApiException.badRequest("wrong_password");
        if (next.length() < 6) throw ApiException.badRequest("too_short");
        staff.update(new Staff(me.id(), me.username(), me.name(), me.role(), me.grant(), me.revoke(), me.active(), hasher.hash(next), me.createdAt()));
        return Map.of("ok", true);
    }
}
