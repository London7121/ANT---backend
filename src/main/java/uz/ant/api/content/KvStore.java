package uz.ant.api.content;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import uz.ant.api.common.Json;

/** Kalit → JSONB (sayt sozlamalari, tarjimalar, token kaliti). */
@Repository
public class KvStore {
    private final JdbcClient db;
    private final Json json;

    public KvStore(JdbcClient db, Json json) {
        this.db = db;
        this.json = json;
    }

    public Optional<JsonNode> get(String key) {
        return db.sql("SELECT value::text FROM kv WHERE key = :k").param("k", key).query(String.class).optional().map(json::parse);
    }

    /** Qatorni qulflaydi (tranzaksiya ichida) — o'qish-o'zgartirish-yozish poygasiz bo'lishi uchun. */
    public Optional<JsonNode> getForUpdate(String key) {
        return db.sql("SELECT value::text FROM kv WHERE key = :k FOR UPDATE").param("k", key).query(String.class).optional().map(json::parse);
    }

    public void put(String key, JsonNode value) {
        db.sql("INSERT INTO kv(key, value) VALUES (:k, cast(:v as jsonb)) ON CONFLICT (key) DO UPDATE SET value = excluded.value")
                .param("k", key).param("v", json.write(value)).update();
    }
}
