package uz.ant.api.staff;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import uz.ant.api.common.Json;
import uz.ant.api.security.Role;
import uz.ant.api.security.Staff;

@Repository
public class StaffRepository {
    private static final String COLS = "id, username, name, role, grant_perms::text AS grant_perms, revoke_perms::text AS revoke_perms, active, hash, created_at";
    private final JdbcClient db;
    private final Json json;

    public StaffRepository(JdbcClient db, Json json) {
        this.db = db;
        this.json = json;
    }

    private List<String> list(String jsonText) {
        JsonNode n = json.parse(jsonText);
        return n.valueStream().map(JsonNode::asString).toList();
    }

    private Staff map(ResultSet rs, int i) throws SQLException {
        return new Staff(rs.getLong("id"), rs.getString("username"), rs.getString("name"), Role.valueOf(rs.getString("role")),
                list(rs.getString("grant_perms")), list(rs.getString("revoke_perms")), rs.getBoolean("active"), rs.getString("hash"),
                rs.getTimestamp("created_at").toInstant());
    }

    public Optional<Staff> findById(long id) {
        return db.sql("SELECT " + COLS + " FROM staff WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public Optional<Staff> findByUsername(String username) {
        return db.sql("SELECT " + COLS + " FROM staff WHERE username = :u").param("u", username).query(this::map).optional();
    }

    public List<Staff> findAll() {
        return db.sql("SELECT " + COLS + " FROM staff ORDER BY id").query(this::map).list();
    }

    public long count() {
        return db.sql("SELECT count(*) FROM staff").query(Long.class).single();
    }

    public long activeSuperAdmins() {
        return db.sql("SELECT count(*) FROM staff WHERE role = 'SUPER_ADMIN' AND active").query(Long.class).single();
    }

    public boolean usernameExists(String username, long exceptId) {
        return db.sql("SELECT count(*) FROM staff WHERE username = :u AND id <> :id").param("u", username).param("id", exceptId).query(Long.class).single() > 0;
    }

    public Staff insert(String username, String name, Role role, List<String> grant, List<String> revoke, boolean active, String hash, Instant createdAt) {
        long id = db.sql("INSERT INTO staff(username, name, role, grant_perms, revoke_perms, active, hash, created_at) VALUES (:u, :n, :r, cast(:g as jsonb), cast(:rv as jsonb), :a, :h, :c) RETURNING id")
                .param("u", username).param("n", name).param("r", role.name()).param("g", json.write(grant)).param("rv", json.write(revoke))
                .param("a", active).param("h", hash).param("c", java.sql.Timestamp.from(createdAt)).query(Long.class).single();
        return findById(id).orElseThrow();
    }

    public void update(Staff s) {
        db.sql("UPDATE staff SET username = :u, name = :n, role = :r, grant_perms = cast(:g as jsonb), revoke_perms = cast(:rv as jsonb), active = :a, hash = :h WHERE id = :id")
                .param("u", s.username()).param("n", s.name()).param("r", s.role().name()).param("g", json.write(s.grant())).param("rv", json.write(s.revoke()))
                .param("a", s.active()).param("h", s.hash()).param("id", s.id()).update();
    }

    public void delete(long id) {
        db.sql("DELETE FROM staff WHERE id = :id").param("id", id).update();
    }
}
