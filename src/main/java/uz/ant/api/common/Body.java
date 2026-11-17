package uz.ant.api.common;

import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * So'rov tanasini tekshirish (eski zod sxemalari o'rnida). Tur noto'g'ri bo'lsa — 400 `invalid`.
 * "Yumshoq" o'qish (`text`) esa noto'g'ri qiymatni bo'sh satrga aylantiradi (zod `.catch('')` kabi).
 */
public final class Body {
    private final JsonNode node;

    private Body(JsonNode node) { this.node = node; }

    /** Tana bo'sh bo'lsa `{}`; obyekt bo'lmasa 400 `invalid`. */
    public static Body of(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return new Body(tools.jackson.databind.node.JsonNodeFactory.instance.objectNode());
        if (!node.isObject()) throw ApiException.badRequest("invalid");
        return new Body(node);
    }

    public JsonNode raw() { return node; }
    public boolean has(String key) { return node.has(key) && !node.get(key).isNull(); }
    public JsonNode get(String key) { return node.get(key); }

    public String requireString(String key) {
        JsonNode v = node.get(key);
        if (v == null || !v.isString()) throw ApiException.badRequest("invalid");
        return v.asString();
    }

    /** Berilmagan bo'lsa bo'sh; berilgan bo'lsa matn bo'lishi shart. */
    public Optional<String> optString(String key) {
        JsonNode v = node.get(key);
        if (v == null || v.isMissingNode()) return Optional.empty();
        if (!v.isString()) throw ApiException.badRequest("invalid");
        return Optional.of(v.asString());
    }

    /** Yumshoq matn: tozalanadi va kesiladi; yo'q yoki noto'g'ri tur → "". */
    public String text(String key, int max) {
        JsonNode v = node.get(key);
        return v != null && v.isString() ? clip(v.asString().trim(), max) : "";
    }

    public Optional<String> optText(String key, int max) {
        return node.has(key) ? Optional.of(text(key, max)) : Optional.empty();
    }

    public boolean requireBoolean(String key) {
        JsonNode v = node.get(key);
        if (v == null || !v.isBoolean()) throw ApiException.badRequest("invalid");
        return v.asBoolean();
    }

    public Optional<Boolean> optBoolean(String key) {
        JsonNode v = node.get(key);
        if (v == null || v.isMissingNode()) return Optional.empty();
        if (!v.isBoolean()) throw ApiException.badRequest("invalid");
        return Optional.of(v.asBoolean());
    }

    /** Yumshoq mantiqiy qiymat: noto'g'ri yoki yo'q bo'lsa `fallback` (zod `.catch(true)` kabi). */
    public boolean flag(String key, boolean fallback) {
        JsonNode v = node.get(key);
        return v != null && v.isBoolean() ? v.asBoolean() : fallback;
    }

    public long requireLong(String key) {
        JsonNode v = node.get(key);
        if (v == null || !v.isIntegralNumber()) throw ApiException.badRequest("invalid");
        return v.asLong();
    }

    public static String clip(String s, int max) { return s.length() <= max ? s : s.substring(0, max); }
}
