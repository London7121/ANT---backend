package uz.ant.api.bootstrap;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import uz.ant.api.common.Counters;
import uz.ant.api.common.Json;
import uz.ant.api.content.ContentRepository;
import uz.ant.api.content.KvStore;
import uz.ant.api.content.Schemas;

/**
 * Eski Node versiyasining `db.json` faylini PostgreSQL'ga ko'chiradi (ID'lar, parol xeshlari va token kaliti saqlanadi,
 * shuning uchun foydalanuvchilar, parollar va sessiyalar o'zgarishsiz ishlaydi). Hammasi BITTA tranzaksiyada.
 */
@Component
public class LegacyJsonImporter {
    private final JdbcClient db;
    private final Json json;
    private final KvStore kv;
    private final ContentRepository content;
    private final Counters counters;

    public LegacyJsonImporter(JdbcClient db, Json json, KvStore kv, ContentRepository content, Counters counters) {
        this.db = db;
        this.json = json;
        this.kv = kv;
        this.content = content;
        this.counters = counters;
    }

    private static String text(JsonNode n, String key, String def) {
        JsonNode v = n.get(key);
        return v != null && v.isString() ? v.asString() : def;
    }

    private static Timestamp ts(JsonNode n, String key) {
        String s = text(n, key, "");
        try {
            return Timestamp.from(Instant.parse(s));
        } catch (java.time.format.DateTimeParseException e) {
            return Timestamp.from(Instant.now());
        }
    }

    private static boolean flag(JsonNode n, String key, boolean def) {
        JsonNode v = n.get(key);
        return v != null && v.isBoolean() ? v.asBoolean() : def;
    }

    private static BigDecimal dec(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v != null && v.isNumber() ? v.decimalValue() : BigDecimal.ZERO;
    }

    private static Long lng(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v != null && v.isIntegralNumber() ? v.asLong() : null;
    }

    private static Integer integer(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v != null && v.isIntegralNumber() ? v.asInt() : null;
    }

    public void run(Path file) {
        JsonNode root;
        try {
            root = json.parse(Files.readString(file));
        } catch (IOException e) {
            throw new IllegalStateException("db.json o'qib bo'lmadi: " + file, e);
        }
        ObjectNode defaults;
        try (var in = new ClassPathResource("seed/default-content.json").getInputStream()) {
            defaults = (ObjectNode) json.mapper().readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        // Sozlamalar: yangi maydonlar standartdan to'ldiriladi ({...default, ...eski})
        ObjectNode settings = ((ObjectNode) defaults.get("settings")).deepCopy();
        if (root.has("settings")) settings.setAll((ObjectNode) root.get("settings"));
        kv.put("settings", settings);
        for (String lang : Schemas.LANGS) {
            JsonNode t = root.path("translations").get(lang);
            kv.put("translations." + lang, t != null && t.isObject() ? t : json.object());
        }
        for (String col : ContentRepository.COLLECTIONS) {
            for (JsonNode item : root.path(col)) {
                ObjectNode copy = ((ObjectNode) item).deepCopy();
                if (col.equals("reviews") && copy.has("userId") && !copy.get("userId").isIntegralNumber()) copy.remove("userId");
                content.insertWithId(col, item.get("id").asLong(), copy);
            }
        }

        // Xodimlar (eng eski format: bitta `admin` obyekti ham qo'llab-quvvatlanadi)
        List<JsonNode> staffList = new ArrayList<>();
        root.path("staff").forEach(staffList::add);
        String secret = text(root, "secret", "");
        if (staffList.isEmpty() && root.has("admin")) {
            JsonNode a = root.get("admin");
            ObjectNode s = json.object().put("id", 1).put("username", text(a, "username", "admin")).put("name", "Super admin").put("role", "SUPER_ADMIN")
                    .put("active", true).put("hash", text(a, "hash", ""));
            staffList.add(s);
            secret = text(a, "secret", secret);
        }
        for (JsonNode s : staffList) {
            db.sql("INSERT INTO staff(id, username, name, role, grant_perms, revoke_perms, active, hash, created_at) VALUES (:id, :u, :n, :r, cast(:g as jsonb), cast(:rv as jsonb), :a, :h, :c)")
                    .param("id", s.get("id").asLong()).param("u", text(s, "username", "")).param("n", text(s, "name", "")).param("r", text(s, "role", "ADMIN"))
                    .param("g", json.write(s.path("grant").isArray() ? s.get("grant") : json.object().arrayNode()))
                    .param("rv", json.write(s.path("revoke").isArray() ? s.get("revoke") : json.object().arrayNode()))
                    .param("a", flag(s, "active", true)).param("h", text(s, "hash", "")).param("c", ts(s, "createdAt")).update();
        }
        if (secret.isEmpty()) secret = DataBootstrap.newSecret();
        kv.put("secret", json.mapper().valueToTree(secret));

        for (JsonNode u : root.path("users")) {
            db.sql("INSERT INTO site_user(id, username, hash, blocked, created_at) VALUES (:id, :u, :h, :b, :c)")
                    .param("id", u.get("id").asLong()).param("u", text(u, "username", "")).param("h", text(u, "hash", "")).param("b", flag(u, "blocked", false)).param("c", ts(u, "createdAt")).update();
            int pos = 1;
            java.util.Set<Long> seen = new java.util.HashSet<>();
            for (JsonNode f : u.path("favorites")) {
                if (f.isIntegralNumber() && seen.add(f.asLong())) {
                    db.sql("INSERT INTO user_favorite(user_id, product_id, position) VALUES (:u, :p, :pos)").param("u", u.get("id").asLong()).param("p", f.asLong()).param("pos", pos++).update();
                }
            }
        }
        for (JsonNode m : root.path("messages")) {
            db.sql("INSERT INTO message(id, name, phone, message, read, created_at) VALUES (:id, :n, :p, :m, :r, :c)")
                    .param("id", m.get("id").asLong()).param("n", text(m, "name", "")).param("p", text(m, "phone", "")).param("m", text(m, "message", ""))
                    .param("r", flag(m, "read", false)).param("c", ts(m, "createdAt")).update();
        }
        for (JsonNode m : root.path("media")) {
            db.sql("INSERT INTO media(id, type, name, url, thumb, width, height, size, created_at) VALUES (:id, :t, :n, :u, :th, :w, :h, :s, :c)")
                    .param("id", m.get("id").asLong()).param("t", text(m, "type", "image")).param("n", text(m, "name", "")).param("u", text(m, "url", ""))
                    .param("th", m.has("thumb") ? text(m, "thumb", null) : null).param("w", integer(m, "width")).param("h", integer(m, "height"))
                    .param("s", lng(m, "size") == null ? 0L : lng(m, "size")).param("c", ts(m, "createdAt")).update();
        }

        // Ombor
        JsonNode warehouses = root.has("warehouses") ? root.get("warehouses") : json.object().arrayNode().add(json.object().put("id", 1).put("name", "Asosiy ombor"));
        for (JsonNode w : warehouses) {
            db.sql("INSERT INTO warehouse(id, name, address, note, active) VALUES (:id, :n, :a, :no, :ac)")
                    .param("id", w.get("id").asLong()).param("n", text(w, "name", "")).param("a", text(w, "address", "")).param("no", text(w, "note", "")).param("ac", flag(w, "active", true)).update();
        }
        for (JsonNode s : root.path("suppliers")) {
            db.sql("INSERT INTO supplier(id, name, phone, address, note, active) VALUES (:id, :n, :p, :a, :no, :ac)")
                    .param("id", s.get("id").asLong()).param("n", text(s, "name", "")).param("p", text(s, "phone", "")).param("a", text(s, "address", ""))
                    .param("no", text(s, "note", "")).param("ac", flag(s, "active", true)).update();
        }
        for (JsonNode c : root.path("catalog")) {
            long id = c.get("id").asLong();
            db.sql("INSERT INTO catalog_item(id, sku, name, category, unit, cost, price, min_stock, image, note, active, created_at) VALUES (:id, :sku, :n, :cat, :u, :cost, :price, :min, :img, :note, :act, :c)")
                    .param("id", id).param("sku", text(c, "sku", "")).param("n", text(c, "name", "")).param("cat", text(c, "category", "")).param("u", text(c, "unit", "pcs"))
                    .param("cost", dec(c, "cost")).param("price", dec(c, "price")).param("min", dec(c, "minStock")).param("img", text(c, "image", "")).param("note", text(c, "note", ""))
                    .param("act", flag(c, "active", true)).param("c", ts(c, "createdAt")).update();
            int pos = 1;
            for (JsonNode code : c.path("barcodes")) {
                if (code.isString()) db.sql("INSERT INTO catalog_barcode(item_id, barcode, position) VALUES (:i, :b, :p)").param("i", id).param("b", code.asString()).param("p", pos++).update();
            }
        }
        for (JsonNode m : root.path("movements")) {
            long id = m.get("id").asLong();
            JsonNode by = m.path("createdBy"), cb = m.path("cancelledBy");
            db.sql("""
                    INSERT INTO movement(id, number, type, date, warehouse_id, to_warehouse_id, supplier_id, counterparty, reason, note, status, created_by_id, created_by_name, created_at,
                                         cancelled_by_id, cancelled_by_name, cancelled_at, cancel_reason)
                    VALUES (:id, :num, :type, :date, :wh, :to, :sup, :cp, :reason, :note, :status, :bid, :bn, :c, :cbid, :cbn, :cat, :cr)""")
                    .param("id", id).param("num", text(m, "number", "")).param("type", text(m, "type", "RECEIPT")).param("date", LocalDate.parse(text(m, "date", LocalDate.now().toString())))
                    .param("wh", m.get("warehouseId").asLong()).param("to", lng(m, "toWarehouseId")).param("sup", lng(m, "supplierId")).param("cp", text(m, "counterparty", ""))
                    .param("reason", text(m, "reason", "")).param("note", text(m, "note", "")).param("status", text(m, "status", "posted"))
                    .param("bid", lng(by, "id") == null ? 0L : lng(by, "id")).param("bn", text(by, "name", "")).param("c", ts(m, "createdAt"))
                    .param("cbid", lng(cb, "id")).param("cbn", cb.isMissingNode() ? null : text(cb, "name", "")).param("cat", m.has("cancelledAt") ? ts(m, "cancelledAt") : null)
                    .param("cr", m.has("cancelReason") ? text(m, "cancelReason", "") : null).update();
            int no = 1;
            for (JsonNode l : m.path("lines")) {
                db.sql("INSERT INTO movement_line(movement_id, line_no, item_id, qty, price) VALUES (:m, :n, :i, :q, :p)")
                        .param("m", id).param("n", no++).param("i", l.get("itemId").asLong()).param("q", dec(l, "qty")).param("p", dec(l, "price")).update();
            }
        }
        for (JsonNode t : root.path("transactions")) {
            JsonNode by = t.path("createdBy");
            db.sql("INSERT INTO ledger_transaction(id, date, type, category, amount, method, counterparty, supplier_id, note, created_by_id, created_by_name, created_at) VALUES (:id, :date, :type, :cat, :amt, :m, :cp, :sup, :note, :bid, :bn, :c)")
                    .param("id", t.get("id").asLong()).param("date", LocalDate.parse(text(t, "date", LocalDate.now().toString()))).param("type", text(t, "type", "expense"))
                    .param("cat", text(t, "category", "")).param("amt", dec(t, "amount")).param("m", text(t, "method", "cash")).param("cp", text(t, "counterparty", ""))
                    .param("sup", lng(t, "supplierId")).param("note", text(t, "note", "")).param("bid", lng(by, "id") == null ? 0L : lng(by, "id")).param("bn", text(by, "name", ""))
                    .param("c", ts(t, "createdAt")).update();
        }
        // Hujjat raqamlari hisoblagichlari
        for (var e : root.path("counters").properties()) if (e.getValue().isIntegralNumber()) counters.raiseTo("doc:" + e.getKey(), e.getValue().asLong());

        // Identity ketma-ketliklari: keyingi yozuvlar import qilingan ID'lardan keyin davom etsin
        for (String table : List.of("staff", "site_user", "message", "media", "warehouse", "supplier", "catalog_item", "movement", "ledger_transaction")) {
            db.sql("SELECT setval(pg_get_serial_sequence('" + table + "', 'id'), GREATEST((SELECT COALESCE(max(id), 0) FROM " + table + "), 1), (SELECT max(id) IS NOT NULL FROM " + table + "))")
                    .query().singleValue();
        }
    }
}
