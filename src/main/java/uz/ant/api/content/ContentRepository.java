package uz.ant.api.content;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import uz.ant.api.common.Counters;
import uz.ant.api.common.Json;

/**
 * Sayt to'plamlari (banners, products, posts, news, reviews) — JSONB hujjatlar.
 * Tartib `position` ustunida; `id` hujjat ichida emas, ustunda saqlanadi va o'qilganda qo'shiladi.
 */
@Repository
public class ContentRepository {
    public static final List<String> COLLECTIONS = List.of("banners", "products", "posts", "news", "reviews");
    private final JdbcClient db;
    private final Json json;
    private final Counters counters;

    public ContentRepository(JdbcClient db, Json json, Counters counters) {
        this.db = db;
        this.json = json;
        this.counters = counters;
    }

    private ObjectNode withId(long id, String data) {
        ObjectNode n = (ObjectNode) json.parse(data);
        ObjectNode out = json.object();
        out.put("id", id);
        out.setAll(n);
        return out;
    }

    public List<ObjectNode> list(String collection) {
        return db.sql("SELECT id, data::text FROM content_item WHERE collection = :c ORDER BY position, id").param("c", collection)
                .query((rs, i) -> withId(rs.getLong("id"), rs.getString("data"))).list();
    }

    public Optional<ObjectNode> find(String collection, long id) {
        return db.sql("SELECT id, data::text FROM content_item WHERE collection = :c AND id = :id").param("c", collection).param("id", id)
                .query((rs, i) -> withId(rs.getLong("id"), rs.getString("data"))).optional();
    }

    /** Yangi element oxiriga qo'shiladi; ID hisoblagichdan olinadi. */
    public ObjectNode insert(String collection, ObjectNode data) {
        long id = counters.next("content:" + collection);
        insertWithId(collection, id, data);
        return withId(id, json.write(data));
    }

    /** Import/seed uchun: berilgan ID bilan. */
    public void insertWithId(String collection, long id, ObjectNode data) {
        ObjectNode clean = data.deepCopy();
        clean.remove("id");
        db.sql("INSERT INTO content_item(collection, id, position, data) VALUES (:c, :id, COALESCE((SELECT max(position) FROM content_item WHERE collection = :c), 0) + 1, cast(:d as jsonb))")
                .param("c", collection).param("id", id).param("d", json.write(clean)).update();
        counters.raiseTo("content:" + collection, id);
    }

    /** To'liq almashtirish (PUT): tanadagi `id` e'tiborga olinmaydi. */
    public Optional<ObjectNode> replace(String collection, long id, ObjectNode data) {
        ObjectNode clean = data.deepCopy();
        clean.remove("id");
        int n = db.sql("UPDATE content_item SET data = cast(:d as jsonb) WHERE collection = :c AND id = :id")
                .param("d", json.write(clean)).param("c", collection).param("id", id).update();
        return n == 0 ? Optional.empty() : Optional.of(withId(id, json.write(clean)));
    }

    public boolean delete(String collection, long id) {
        return db.sql("DELETE FROM content_item WHERE collection = :c AND id = :id").param("c", collection).param("id", id).update() > 0;
    }

    /** Ko'rsatilgan ID'lar shu tartibda birinchi, qolganlari ortidan (mavjud bo'lmagan va takroriy ID'lar tashlanadi). */
    public void reorder(String collection, List<Long> ids) {
        List<Long> existing = db.sql("SELECT id FROM content_item WHERE collection = :c ORDER BY position, id").param("c", collection).query(Long.class).list();
        Set<Long> known = new LinkedHashSet<>(existing);
        List<Long> order = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (Long id : ids) if (known.contains(id) && seen.add(id)) order.add(id);
        for (Long id : existing) if (!seen.contains(id)) order.add(id);
        int pos = 1;
        for (Long id : order) {
            db.sql("UPDATE content_item SET position = :p WHERE collection = :c AND id = :id").param("p", pos++).param("c", collection).param("id", id).update();
        }
    }

    public boolean productExists(long id) {
        return db.sql("SELECT count(*) FROM content_item WHERE collection = 'products' AND id = :id").param("id", id).query(Long.class).single() > 0;
    }

    public List<ObjectNode> reviewsOfUser(long userId) {
        return db.sql("SELECT id, data::text FROM content_item WHERE collection = 'reviews' AND jsonb_typeof(data -> 'userId') = 'number' AND (data ->> 'userId')::bigint = :u ORDER BY position, id")
                .param("u", userId).query((rs, i) -> withId(rs.getLong("id"), rs.getString("data"))).list();
    }

    public int deleteReviewsOfUser(long userId) {
        return db.sql("DELETE FROM content_item WHERE collection = 'reviews' AND jsonb_typeof(data -> 'userId') = 'number' AND (data ->> 'userId')::bigint = :u")
                .param("u", userId).update();
    }

    public static boolean visible(JsonNode item) {
        JsonNode v = item.get("visible");
        return v == null || !v.isBoolean() || v.asBoolean();
    }
}
