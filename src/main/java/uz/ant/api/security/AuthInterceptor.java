package uz.ant.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import uz.ant.api.account.SiteUser;
import uz.ant.api.account.SiteUserRepository;
import uz.ant.api.common.ApiException;
import uz.ant.api.staff.StaffRepository;

/**
 * Autentifikatsiya: `/api/admin/**` (kirishdan tashqari) — xodim tokeni, `/api/me/**` — mijoz tokeni.
 * Natija so'rov atributiga qo'yiladi (`staff` / `siteUser`), `@RequirePermission` shu yerda tekshiriladi.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    public static final String STAFF = "staff";
    public static final String SITE_USER = "siteUser";

    private final TokenService tokens;
    private final StaffRepository staff;
    private final SiteUserRepository users;

    public AuthInterceptor(TokenService tokens, StaffRepository staff, SiteUserRepository users) {
        this.tokens = tokens;
        this.staff = staff;
        this.users = users;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) return true; // CORS preflight
        String path = req.getRequestURI();
        String header = req.getHeader(HttpHeaders.AUTHORIZATION);
        if (path.startsWith("/api/admin/") && !path.equals("/api/admin/login")) {
            Long id = tokens.verify(header, TokenService.Kind.STAFF);
            Staff s = id == null ? null : staff.findById(id).orElse(null);
            if (s == null || !s.active()) throw ApiException.unauthorized();
            req.setAttribute(STAFF, s);
            if (handler instanceof HandlerMethod hm) {
                RequirePermission need = hm.getMethodAnnotation(RequirePermission.class);
                if (need != null && !Permissions.can(s, need.value())) throw ApiException.forbiddenPermission(need.value());
            }
        } else if (path.startsWith("/api/me")) {
            Long id = tokens.verify(header, TokenService.Kind.USER);
            SiteUser u = id == null ? null : users.findById(id).orElse(null);
            if (u == null) throw ApiException.unauthorized();
            if (u.blocked()) throw ApiException.forbidden("blocked");
            req.setAttribute(SITE_USER, u);
        }
        return true;
    }
}
