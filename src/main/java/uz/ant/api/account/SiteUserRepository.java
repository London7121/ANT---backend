package uz.ant.api.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SiteUserRepository {
    private static final String COLS = "id, username, hash, blocked, created_at";
    private final JdbcClient db;

    public SiteUserRepository(JdbcClient db) { this.db = db; }

    private SiteUser map(ResultSet rs, int i) throws SQLException {
        return new SiteUser(rs.getLong("id"), rs.getString("username"), rs.getString("hash"), rs.getBoolean("blocked"), rs.getTimestamp("created_at").toInstant());
    }

    public Optional<SiteUser> findById(long id) {
        return db.sql("SELECT " + COLS + " FROM site_user WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public Optional<SiteUser> findByUsername(String username) {
        return db.sql("SELECT " + COLS + " FROM site_user WHERE lower(username) = lower(:u)").param("u", username).query(this::map).optional();
    }

    public List<SiteUser> findAll() {
        return db.sql("SELECT " + COLS + " FROM site_user ORDER BY id").query(this::map).list();
    }

    /** Username band bo'lsa (parallel so'rov ham) DuplicateKeyException. */
    public SiteUser insert(String username, String hash) {
        long id = db.sql("INSERT INTO site_user(username, hash) VALUES (:u, :h) RETURNING id").param("u", username).param("h", hash).query(Long.class).single();
        return findById(id).orElseThrow();
    }

    public boolean setBlocked(long id, boolean blocked) {
        return db.sql("UPDATE site_user SET blocked = :b WHERE id = :id").param("b", blocked).param("id", id).update() > 0;
    }

    public void delete(long id) {
        db.sql("DELETE FROM site_user WHERE id = :id").param("id", id).update();
    }

    public List<Long> favorites(long userId) {
        return db.sql("SELECT product_id FROM user_favorite WHERE user_id = :u ORDER BY position").param("u", userId).query(Long.class).list();
    }

    public void setFavorites(long userId, List<Long> ids) {
        db.sql("DELETE FROM user_favorite WHERE user_id = :u").param("u", userId).update();
        int pos = 1;
        for (Long id : ids) {
            db.sql("INSERT INTO user_favorite(user_id, product_id, position) VALUES (:u, :p, :pos)").param("u", userId).param("p", id).param("pos", pos++).update();
        }
    }
}
