package uz.ant.api.content;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import uz.ant.api.common.ApiException;
import uz.ant.api.common.Json;
import uz.ant.api.common.Time;

/**
 * Sayt kontenti uchun tekshiruv va tozalash (Node versiyasidagi zod sxemalari o'rnida).
 * Qoidalar bir xil: yuborilmagan maydon → standart qiymat, tur noto'g'ri → 400 `invalid`,
 * noma'lum maydonlar saqlanadi (`looseObject`), ko'p tilli matnda yetishmagan til → "".
 */
@Component
public class Schemas {
    public static final List<String> LANGS = List.of("uz", "ru", "en");
    static final List<String> SECTION_KEYS = List.of("hero", "marquee", "stats", "products", "promo", "feed", "news", "reviews", "contact");

    private final Json json;
    private final Time time;

    public Schemas(Json json, Time time) {
        this.json = json;
        this.time = time;
    }

    private static ApiException invalid() { return ApiException.badRequest("invalid"); }

    // ---------- maydon yordamchilari (src — tozalanayotgan obyekt; natija out ga yoziladi) ----------
    static void str(ObjectNode src, ObjectNode out, String key, String def) {
        JsonNode v = src.get(key);
        if (v == null) out.put(key, def);
        else if (v.isString()) out.put(key, v.asString());
        else throw invalid();
    }

    static void optStr(ObjectNode src, ObjectNode out, String key) {
        JsonNode v = src.get(key);
        if (v == null) out.remove(key);
        else if (v.isString()) out.put(key, v.asString());
        else throw invalid();
    }

    static void bool(ObjectNode src, ObjectNode out, String key, boolean def) {
        JsonNode v = src.get(key);
        if (v == null) out.put(key, def);
        else if (v.isBoolean()) out.put(key, v.asBoolean());
        else throw invalid();
    }

    static void num(ObjectNode src, ObjectNode out, String key, double def) {
        JsonNode v = src.get(key);
        if (v == null) { if (def == Math.rint(def)) out.put(key, (long) def); else out.put(key, def); }
        else if (v.isNumber()) out.set(key, v);
        else throw invalid();
    }

    static void enumOf(ObjectNode src, ObjectNode out, String key, String def, List<String> allowed) {
        JsonNode v = src.get(key);
        if (v == null) out.put(key, def);
        else if (v.isString() && allowed.contains(v.asString())) out.put(key, v.asString());
        else throw invalid();
    }

    void strList(ObjectNode src, ObjectNode out, String key) {
        JsonNode v = src.get(key);
        if (v == null) { out.set(key, json.object().arrayNode()); return; }
        if (!v.isArray()) throw invalid();
        ArrayNode a = json.object().arrayNode();
        for (JsonNode e : v) { if (!e.isString()) throw invalid(); a.add(e.asString()); }
        out.set(key, a);
    }

    /** Ko'p tilli matn: yuborilmasa {uz:'',ru:'',en:''}; yetishmagan til "". */
    void loc(ObjectNode src, ObjectNode out, String key) {
        JsonNode v = src.get(key);
        ObjectNode o = json.object();
        if (v != null && !v.isObject()) throw invalid();
        for (String l : LANGS) {
            JsonNode t = v == null ? null : v.get(l);
            if (t == null) o.put(l, "");
            else if (t.isString()) o.put(l, t.asString());
            else throw invalid();
        }
        out.set(key, o);
    }

    /** Sozlamalarda: faqat yuborilgan tillar saqlanadi (mavjud qiymatni buzmaslik uchun). */
    ObjectNode locPatch(JsonNode v) {
        if (!v.isObject()) throw invalid();
        ObjectNode o = json.object();
        for (String l : LANGS) {
            JsonNode t = v.get(l);
            if (t == null) continue;
            if (!t.isString()) throw invalid();
            o.put(l, t.asString());
        }
        return o;
    }

    // ---------- to'plamlar ----------
    public ObjectNode parseItem(String collection, JsonNode body) {
        if (body == null || !body.isObject()) throw invalid();
        ObjectNode src = (ObjectNode) body;
        ObjectNode out = src.deepCopy();
        out.remove("id");
        Supplier<String> today = () -> time.today().toString();
        switch (collection) {
            case "banners" -> {
                str(src, out, "image", ""); str(src, out, "accent", "#B59E67"); loc(src, out, "title"); bool(src, out, "visible", true);
            }
            case "products" -> {
                loc(src, out, "name"); loc(src, out, "description"); strList(src, out, "sizes"); strList(src, out, "colors");
                str(src, out, "image", ""); strList(src, out, "images"); bool(src, out, "isNew", false); bool(src, out, "visible", true);
            }
            case "posts" -> {
                enumOf(src, out, "type", "photo", List.of("video", "photo", "post")); loc(src, out, "title"); loc(src, out, "text");
                str(src, out, "image", ""); str(src, out, "video", ""); num(src, out, "height", 0); str(src, out, "date", today.get()); bool(src, out, "visible", true);
            }
            case "news" -> {
                loc(src, out, "title"); loc(src, out, "text"); loc(src, out, "body"); str(src, out, "image", ""); strList(src, out, "gallery");
                str(src, out, "date", today.get()); bool(src, out, "visible", true);
            }
            case "reviews" -> {
                str(src, out, "name", "");
                JsonNode r = src.get("rating");
                if (r == null) out.put("rating", 5);
                else if (r.isIntegralNumber() && r.asLong() >= 1 && r.asLong() <= 5) out.put("rating", r.asInt());
                else throw invalid();
                loc(src, out, "text"); optStr(src, out, "avatar"); bool(src, out, "visible", true);
                // userId faqat butun son bo'lishi mumkin (bazadagi indeks shunga tayanadi)
                JsonNode uid = src.get("userId");
                if (uid != null && !uid.isIntegralNumber()) out.remove("userId");
            }
            default -> throw ApiException.notFound();
        }
        return out;
    }

    // ---------- sayt sozlamalari (qisman yangilash) ----------
    private void typed(ObjectNode out, String key, JsonNode v, String type) {
        boolean ok = switch (type) {
            case "string" -> v.isString();
            case "number" -> v.isNumber();
            case "boolean" -> v.isBoolean();
            default -> false;
        };
        if (!ok) throw invalid();
        out.set(key, v);
    }

    private ObjectNode looseSection(JsonNode v, Map<String, String> typed) {
        if (!v.isObject()) throw invalid();
        ObjectNode o = ((ObjectNode) v).deepCopy();
        for (var e : typed.entrySet()) {
            JsonNode f = v.get(e.getKey());
            if (f != null) typed(o, e.getKey(), f, e.getValue());
        }
        return o;
    }

    /** Yuborilgan bo'limlarni tekshiradi; noma'lum kalitlar saqlanadi. */
    public ObjectNode parseSettingsPatch(JsonNode body) {
        if (body == null || !body.isObject()) throw invalid();
        ObjectNode out = ((ObjectNode) body).deepCopy();
        for (var e : body.properties()) {
            String k = e.getKey();
            JsonNode v = e.getValue();
            switch (k) {
                case "brand" -> {
                    ObjectNode o = looseSection(v, Map.of("logo", "string"));
                    JsonNode name = v.get("name");
                    if (name != null && (!name.isString() || name.asString().isEmpty())) throw invalid();
                    out.set(k, o);
                }
                case "phone", "email", "telegram", "instagram" -> typed(out, k, v, "string");
                case "address", "hours" -> out.set(k, locPatch(v));
                case "stats" -> out.set(k, looseSection(v, Map.of("clients", "number", "products", "number", "years", "number", "cities", "number")));
                case "theme" -> out.set(k, looseSection(v, Map.of("accent", "string", "accentDark", "string")));
                case "seo" -> {
                    ObjectNode o = looseSection(v, Map.of("siteUrl", "string", "ogImage", "string"));
                    for (String f : List.of("title", "description")) if (v.has(f)) o.set(f, locPatch(v.get(f)));
                    out.set(k, o);
                }
                case "sections" -> {
                    if (!v.isArray()) throw invalid();
                    ArrayNode a = json.object().arrayNode();
                    for (JsonNode s : v) {
                        JsonNode key = s.get("key"), vis = s.get("visible");
                        if (!s.isObject() || key == null || !key.isString() || !SECTION_KEYS.contains(key.asString()) || vis == null || !vis.isBoolean()) throw invalid();
                        a.add(json.object().put("key", key.asString()).put("visible", vis.asBoolean()));
                    }
                    out.set(k, a);
                }
                case "hero" -> {
                    ObjectNode o = looseSection(v, Map.of("slideMs", "number"));
                    JsonNode ms = v.get("slideMs");
                    if (ms != null && (ms.asDouble() < 1500 || ms.asDouble() > 30000)) throw invalid();
                    out.set(k, o);
                }
                case "promo" -> out.set(k, looseSection(v, Map.of("image", "string", "href", "string")));
                default -> { /* noma'lum kalit — saqlanadi */ }
            }
        }
        return out;
    }

    /** Tarjimalar: kalit → matn yoki matnlar ro'yxati. */
    public ObjectNode parseTranslations(JsonNode body) {
        if (body == null || !body.isObject()) throw invalid();
        for (var e : body.properties()) {
            JsonNode v = e.getValue();
            if (v.isString()) continue;
            if (v.isArray() && v.valueStream().allMatch(JsonNode::isString)) continue;
            throw invalid();
        }
        return ((ObjectNode) body).deepCopy();
    }
}
