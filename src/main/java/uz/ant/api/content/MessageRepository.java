package uz.ant.api.content;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.ant.api.common.Time;

/** Aloqa formasidan kelgan xabarlar (eng yangisi birinchi). */
@Repository
public class MessageRepository {
    private final JdbcClient db;

    public MessageRepository(JdbcClient db) { this.db = db; }

    private static Map<String, Object> map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("name", rs.getString("name"));
        m.put("phone", rs.getString("phone"));
        m.put("message", rs.getString("message"));
        m.put("read", rs.getBoolean("read"));
        m.put("createdAt", Time.iso(rs.getTimestamp("created_at").toInstant()));
        return m;
    }

    private static final String COLS = "id, name, phone, message, read, created_at";

    public List<Map<String, Object>> list() {
        return db.sql("SELECT " + COLS + " FROM message ORDER BY id DESC").query(MessageRepository::map).list();
    }

    public List<Map<String, Object>> latest(int limit) {
        return db.sql("SELECT " + COLS + " FROM message ORDER BY id DESC LIMIT :n").param("n", limit).query(MessageRepository::map).list();
    }

    public Optional<Map<String, Object>> find(long id) {
        return db.sql("SELECT " + COLS + " FROM message WHERE id = :id").param("id", id).query(MessageRepository::map).optional();
    }

    public void insert(String name, String phone, String message) {
        db.sql("INSERT INTO message(name, phone, message) VALUES (:n, :p, :m)").param("n", name).param("p", phone).param("m", message).update();
    }

    public boolean setRead(long id, boolean read) {
        return db.sql("UPDATE message SET read = :r WHERE id = :id").param("r", read).param("id", id).update() > 0;
    }

    public void delete(long id) {
        db.sql("DELETE FROM message WHERE id = :id").param("id", id).update();
    }
}
