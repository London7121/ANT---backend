package uz.ant.api.media;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.ant.api.common.Time;

/** Media kutubxonasi (yuklangan rasm/video yozuvlari). Eng yangisi birinchi. */
@Repository
public class MediaRepository {
    private static final String COLS = "id, type, name, url, thumb, width, height, size, created_at";
    private final JdbcClient db;

    public MediaRepository(JdbcClient db) { this.db = db; }

    private static Map<String, Object> map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("type", rs.getString("type"));
        m.put("name", rs.getString("name"));
        m.put("url", rs.getString("url"));
        if (rs.getString("thumb") != null) m.put("thumb", rs.getString("thumb"));
        if (rs.getObject("width") != null) m.put("width", rs.getInt("width"));
        if (rs.getObject("height") != null) m.put("height", rs.getInt("height"));
        m.put("size", rs.getLong("size"));
        m.put("createdAt", Time.iso(rs.getTimestamp("created_at").toInstant()));
        return m;
    }

    public List<Map<String, Object>> list() {
        return db.sql("SELECT " + COLS + " FROM media ORDER BY id DESC").query(MediaRepository::map).list();
    }

    public Optional<Map<String, Object>> find(long id) {
        return db.sql("SELECT " + COLS + " FROM media WHERE id = :id").param("id", id).query(MediaRepository::map).optional();
    }

    public Map<String, Object> insert(String type, String name, String url, String thumb, Integer width, Integer height, long size) {
        long id = db.sql("INSERT INTO media(type, name, url, thumb, width, height, size) VALUES (:t, :n, :u, :th, :w, :h, :s) RETURNING id")
                .param("t", type).param("n", name).param("u", url).param("th", thumb).param("w", width).param("h", height).param("s", size)
                .query(Long.class).single();
        return find(id).orElseThrow();
    }

    public boolean delete(long id) {
        return db.sql("DELETE FROM media WHERE id = :id").param("id", id).update() > 0;
    }
}
