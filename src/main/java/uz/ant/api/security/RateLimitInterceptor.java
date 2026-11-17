package uz.ant.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import uz.ant.api.common.ApiException;
import uz.ant.api.config.AntProperties;

/** Xotirada ishlovchi oddiy cheklov (har endpoint + IP uchun alohida oyna). */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    private final AntProperties props;
    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public RateLimitInterceptor(AntProperties props) { this.props = props; }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if (!props.rateLimitEnabled() || !(handler instanceof HandlerMethod hm)) return true;
        RateLimit limit = hm.getMethodAnnotation(RateLimit.class);
        if (limit == null) return true;
        long now = System.currentTimeMillis();
        long windowMs = limit.windowSeconds() * 1000L;
        Deque<Long> q = hits.computeIfAbsent(hm.getMethod().toGenericString() + "|" + clientIp(req), k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() >= windowMs) q.pollFirst();
            if (q.size() >= limit.max()) throw new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "too_many_requests");
            q.addLast(now);
        }
        if (hits.size() > 10_000) hits.entrySet().removeIf(e -> e.getValue().isEmpty() || now - e.getValue().peekLast() >= windowMs);
        return true;
    }

    /** Proksi ortida: X-Forwarded-For dagi OXIRGI manzil (Express `trust proxy = 1` bilan bir xil). */
    String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (props.trustProxy() && xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            return parts[parts.length - 1].trim();
        }
        return req.getRemoteAddr();
    }
}
