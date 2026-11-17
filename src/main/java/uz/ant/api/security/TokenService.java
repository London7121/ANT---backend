package uz.ant.api.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.ant.api.common.Json;
import uz.ant.api.content.KvStore;

/**
 * HMAC-SHA256 bilan imzolangan token: `base64url(json).base64url(imzo)` — eski Node formati bilan bir xil,
 * shuning uchun ko'chirishdan keyin mavjud sessiyalar (kalit `kv.secret` ga ko'chadi) ishlashda davom etadi.
 * Xodim tokeni 12 soat, mijoz tokeni 1 yil (bir marta kirib, doimiy yurishi uchun).
 */
@Component
public class TokenService {
    public enum Kind {
        STAFF("staff", 12L * 60 * 60 * 1000), USER("user", 365L * 24 * 60 * 60 * 1000);
        final String code;
        final long ttlMs;
        Kind(String code, long ttlMs) { this.code = code; this.ttlMs = ttlMs; }
    }

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private final KvStore kv;
    private final JsonMapper json;
    private volatile byte[] secret;

    public TokenService(KvStore kv, Json json) {
        this.kv = kv;
        this.json = json.mapper();
    }

    private byte[] secret() {
        byte[] s = secret;
        if (s == null) {
            String value = kv.get("secret").map(JsonNode::asString).orElseThrow(() -> new IllegalStateException("kv.secret yo'q — bootstrap ishlamagan"));
            secret = s = value.getBytes(StandardCharsets.UTF_8);
        }
        return s;
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret(), "HmacSHA256"));
            return B64.encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String issue(Kind kind, long id) {
        var payload = json.createObjectNode().put("k", kind.code).put("id", id).put("exp", System.currentTimeMillis() + kind.ttlMs);
        String body = B64.encodeToString(json.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
        return body + "." + sign(body);
    }

    /** To'g'ri imzolangan, muddati o'tmagan va turi mos token bo'lsa — egasining ID'si. */
    public Long verify(String authorizationHeader, Kind kind) {
        String token = authorizationHeader == null ? "" : authorizationHeader.replaceFirst("^Bearer ", "");
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) return null;
        String body = token.substring(0, dot);
        String signature = token.substring(dot + 1);
        if (!MessageDigest.isEqual(signature.getBytes(StandardCharsets.UTF_8), sign(body).getBytes(StandardCharsets.UTF_8))) return null;
        try {
            JsonNode p = json.readTree(new String(B64D.decode(body), StandardCharsets.UTF_8));
            if (!kind.code.equals(p.path("k").asString(null))) return null;
            if (p.path("exp").asLong(0) <= System.currentTimeMillis()) return null;
            return p.path("id").isIntegralNumber() ? p.get("id").asLong() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
