package uz.ant.api.warehouse;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Omborlar va yetkazib beruvchilar (oddiy ma'lumotnomalar). */
@Repository
public class RefRepository {
    private final JdbcClient db;

    public RefRepository(JdbcClient db) { this.db = db; }

    // ---- ombor ----
    private static Map<String, Object> warehouse(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new java.util.LinkedHashMap<>(Map.of("id", rs.getLong("id"), "name", rs.getString("name"), "address", rs.getString("address"),
                "note", rs.getString("note"), "active", rs.getBoolean("active")));
    }

    public List<Map<String, Object>> warehouses() {
        return db.sql("SELECT id, name, address, note, active FROM warehouse ORDER BY id").query(RefRepository::warehouse).list();
    }

    public Optional<Map<String, Object>> warehouse(long id) {
        return db.sql("SELECT id, name, address, note, active FROM warehouse WHERE id = :id").param("id", id).query(RefRepository::warehouse).optional();
    }

    public Map<String, Object> saveWarehouse(Long id, String name, String address, String note, boolean active) {
        long newId = id != null ? id : 0;
        if (id == null) {
            newId = db.sql("INSERT INTO warehouse(name, address, note, active) VALUES (:n, :a, :no, :ac) RETURNING id")
                    .param("n", name).param("a", address).param("no", note).param("ac", active).query(Long.class).single();
        } else {
            db.sql("UPDATE warehouse SET name = :n, address = :a, note = :no, active = :ac WHERE id = :id")
                    .param("n", name).param("a", address).param("no", note).param("ac", active).param("id", id).update();
        }
        return warehouse(newId).orElseThrow();
    }

    public boolean warehouseInUse(long id) {
        return db.sql("SELECT count(*) FROM movement WHERE warehouse_id = :id OR to_warehouse_id = :id").param("id", id).query(Long.class).single() > 0;
    }

    public void deleteWarehouse(long id) { db.sql("DELETE FROM warehouse WHERE id = :id").param("id", id).update(); }

    public boolean warehouseActive(long id) {
        return db.sql("SELECT count(*) FROM warehouse WHERE id = :id AND active").param("id", id).query(Long.class).single() > 0;
    }

    // ---- yetkazib beruvchi ----
    private static Map<String, Object> supplier(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new java.util.LinkedHashMap<>(Map.of("id", rs.getLong("id"), "name", rs.getString("name"), "phone", rs.getString("phone"),
                "address", rs.getString("address"), "note", rs.getString("note"), "active", rs.getBoolean("active")));
    }

    public List<Map<String, Object>> suppliers() {
        return db.sql("SELECT id, name, phone, address, note, active FROM supplier ORDER BY id").query(RefRepository::supplier).list();
    }

    public Optional<Map<String, Object>> supplier(long id) {
        return db.sql("SELECT id, name, phone, address, note, active FROM supplier WHERE id = :id").param("id", id).query(RefRepository::supplier).optional();
    }

    public Map<String, Object> saveSupplier(Long id, String name, String phone, String address, String note, boolean active) {
        long newId = id != null ? id : 0;
        if (id == null) {
            newId = db.sql("INSERT INTO supplier(name, phone, address, note, active) VALUES (:n, :p, :a, :no, :ac) RETURNING id")
                    .param("n", name).param("p", phone).param("a", address).param("no", note).param("ac", active).query(Long.class).single();
        } else {
            db.sql("UPDATE supplier SET name = :n, phone = :p, address = :a, note = :no, active = :ac WHERE id = :id")
                    .param("n", name).param("p", phone).param("a", address).param("no", note).param("ac", active).param("id", id).update();
        }
        return supplier(newId).orElseThrow();
    }

    public boolean supplierInUse(long id) {
        return db.sql("SELECT (SELECT count(*) FROM movement WHERE supplier_id = :id) + (SELECT count(*) FROM ledger_transaction WHERE supplier_id = :id)")
                .param("id", id).query(Long.class).single() > 0;
    }

    public void deleteSupplier(long id) { db.sql("DELETE FROM supplier WHERE id = :id").param("id", id).update(); }

    public boolean supplierExists(long id) {
        return db.sql("SELECT count(*) FROM supplier WHERE id = :id").param("id", id).query(Long.class).single() > 0;
    }
}
