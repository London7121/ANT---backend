package uz.ant.api.account;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import tools.jackson.databind.JsonNode;
import uz.ant.api.common.ApiException;
import uz.ant.api.common.Body;
import uz.ant.api.common.Time;
import uz.ant.api.security.PasswordHasher;
import uz.ant.api.security.RateLimit;
import uz.ant.api.security.TokenService;

/** Landing foydalanuvchilari: ro'yxatdan o'tish va kirish (faqat login + parol). */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Sayt: kirish", description = "Mijoz ro'yxatdan o'tishi va kirishi")
public class SiteAuthController {
    private static final Pattern USERNAME = Pattern.compile("^[a-zA-Z0-9_.-]{3,32}$");
    private final SiteUserRepository users;
    private final PasswordHasher hasher;
    private final TokenService tokens;

    public SiteAuthController(SiteUserRepository users, PasswordHasher hasher, TokenService tokens) {
        this.users = users;
        this.hasher = hasher;
        this.tokens = tokens;
    }

    public static Map<String, Object> publicUser(SiteUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id());
        m.put("username", u.username());
        m.put("createdAt", Time.iso(u.createdAt()));
        return m;
    }

    private Map<String, Object> session(SiteUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", tokens.issue(TokenService.Kind.USER, u.id()));
        m.put("user", publicUser(u));
        m.put("favorites", users.favorites(u.id()));
        return m;
    }

    @PostMapping("/register")
    @RateLimit(max = 10, windowSeconds = 3600)
    @Operation(summary = "Ro'yxatdan o'tish", description = "username: 3–32 belgi (harf, raqam, _ . -), parol: 4–200 belgi. Band bo'lsa 409 `username_taken`.")
    public Map<String, Object> register(@RequestBody(required = false) JsonNode raw) {
        Body b = Body.of(raw);
        String username = b.requireString("username").trim();
        String password = b.requireString("password");
        if (!USERNAME.matcher(username).matches() || password.length() < 4 || password.length() > 200) throw ApiException.badRequest("invalid");
        if (users.findByUsername(username).isPresent()) throw ApiException.conflict("username_taken");
        try {
            return session(users.insert(username, hasher.hash(password)));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("username_taken");
        }
    }

    @PostMapping("/login")
    @RateLimit(max = 20, windowSeconds = 15 * 60)
    @Operation(summary = "Kirish", description = "Noto'g'ri login/parol — 401 `invalid_credentials`; bloklangan — 403 `blocked`.")
    public Map<String, Object> login(@RequestBody(required = false) JsonNode raw) {
        Body b = Body.of(raw);
        String username = b.requireString("username").trim();
        String password = b.requireString("password");
        SiteUser user = users.findByUsername(username).orElse(null);
        if (user == null || !hasher.matches(password, user.hash())) throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "invalid_credentials");
        if (user.blocked()) throw ApiException.forbidden("blocked");
        return session(user);
    }

}
