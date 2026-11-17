package uz.ant.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Kichik, lekin muhim HTTP himoyalari. */
@Component
public class RequestFilters extends OncePerRequestFilter {
    private static final long MAX_JSON_BYTES = 2L * 1024 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String path = req.getRequestURI();

        if (path.startsWith("/uploads/")) {
            // Yuklangan SVG ichidagi skriptlar ishlamasligi uchun qat'iy sarlavhalar
            res.setHeader("X-Content-Type-Options", "nosniff");
            res.setHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; img-src 'self'; media-src 'self'");
        }

        String type = req.getContentType();
        boolean multipart = type != null && type.toLowerCase().startsWith("multipart/");
        if (path.startsWith("/api/") && !multipart && req.getContentLengthLong() > MAX_JSON_BYTES) {
            res.setStatus(413);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"payload_too_large\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
