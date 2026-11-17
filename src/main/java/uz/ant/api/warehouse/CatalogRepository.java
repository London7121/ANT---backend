package uz.ant.api.warehouse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.ant.api.common.Time;

/** Tovar katalogi (ombor birligi; saytdagi mahsulotlardan alohida). */
@Repository
public class CatalogRepository {
    private static final String COLS = "id, sku, name, category, unit, cost, price, min_stock, image, note, active, created_at";
    private final JdbcClient db;

    public CatalogRepository(JdbcClient db) { this.db = db; }

    public record Item(long id, String sku, String name, String category, String unit, BigDecimal cost, BigDecimal price, BigDecimal minStock,
                       String image, String note, boolean active, Instant createdAt, List<String> barcodes) {
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("sku", sku); m.put("name", name); m.put("category", category); m.put("unit", unit);
            m.put("barcodes", barcodes); m.put("cost", cost); m.put("price", price); m.put("minStock", minStock);
            m.put("image", image); m.put("note", note); m.put("active", active); m.put("createdAt", Time.iso(createdAt));
            return m;
        }
    }

    private List<Item> hydrate(List<Item> raw) {
        Map<Long, List<String>> codes = new java.util.HashMap<>();
        db.sql("SELECT item_id, barcode FROM catalog_barcode ORDER BY item_id, position").query((rs, i) -> {
            codes.computeIfAbsent(rs.getLong("item_id"), k -> new ArrayList<>()).add(rs.getString("barcode"));
            return 0;
        }).list();
        return raw.stream().map(it -> new Item(it.id, it.sku, it.name, it.category, it.unit, it.cost, it.price, it.minStock, it.image, it.note, it.active, it.createdAt,
                codes.getOrDefault(it.id, List.of()))).toList();
    }

    private static Item map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Item(rs.getLong("id"), rs.getString("sku"), rs.getString("name"), rs.getString("category"), rs.getString("unit"), rs.getBigDecimal("cost"),
                rs.getBigDecimal("price"), rs.getBigDecimal("min_stock"), rs.getString("image"), rs.getString("note"), rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant(), List.of());
    }

    public List<Item> findAll() {
        return hydrate(db.sql("SELECT " + COLS + " FROM catalog_item ORDER BY id").query(CatalogRepository::map).list());
    }

    public Optional<Item> findById(long id) {
        return hydrate(db.sql("SELECT " + COLS + " FROM catalog_item WHERE id = :id").param("id", id).query(CatalogRepository::map).list()).stream().findFirst();
    }

    /** SKU yoki shtrix kod bo'yicha (katta-kichik harfga e'tiborsiz). */
    public Optional<Item> findByCode(String code) {
        List<Item> found = hydrate(db.sql("SELECT " + COLS + " FROM catalog_item c WHERE lower(sku) = :c OR EXISTS (SELECT 1 FROM catalog_barcode b WHERE b.item_id = c.id AND lower(b.barcode) = :c) ORDER BY id LIMIT 1")
                .param("c", code).query(CatalogRepository::map).list());
        return found.stream().findFirst();
    }

    public boolean skuTaken(String sku, long exceptId) {
        return db.sql("SELECT count(*) FROM catalog_item WHERE lower(sku) = lower(:s) AND id <> :id").param("s", sku).param("id", exceptId).query(Long.class).single() > 0;
    }

    public boolean barcodeTaken(List<String> codes, long exceptId) {
        if (codes.isEmpty()) return false;
        return db.sql("SELECT count(*) FROM catalog_barcode WHERE barcode IN (:codes) AND item_id <> :id").param("codes", codes).param("id", exceptId).query(Long.class).single() > 0;
    }

    public record Data(String sku, String name, String category, String unit, List<String> barcodes, BigDecimal cost, BigDecimal price,
                       BigDecimal minStock, String image, String note, boolean active) {}

    public Item insert(Data d) {
        long id = db.sql("INSERT INTO catalog_item(sku, name, category, unit, cost, price, min_stock, image, note, active) VALUES (:sku, :name, :cat, :unit, :cost, :price, :min, :img, :note, :act) RETURNING id")
                .param("sku", d.sku).param("name", d.name).param("cat", d.category).param("unit", d.unit).param("cost", d.cost).param("price", d.price)
                .param("min", d.minStock).param("img", d.image).param("note", d.note).param("act", d.active).query(Long.class).single();
        setBarcodes(id, d.barcodes);
        return findById(id).orElseThrow();
    }

    public Item update(long id, Data d) {
        db.sql("UPDATE catalog_item SET sku = :sku, name = :name, category = :cat, unit = :unit, cost = :cost, price = :price, min_stock = :min, image = :img, note = :note, active = :act WHERE id = :id")
                .param("sku", d.sku).param("name", d.name).param("cat", d.category).param("unit", d.unit).param("cost", d.cost).param("price", d.price)
                .param("min", d.minStock).param("img", d.image).param("note", d.note).param("act", d.active).param("id", id).update();
        setBarcodes(id, d.barcodes);
        return findById(id).orElseThrow();
    }

    private void setBarcodes(long id, List<String> codes) {
        db.sql("DELETE FROM catalog_barcode WHERE item_id = :id").param("id", id).update();
        int pos = 1;
        for (String c : codes) db.sql("INSERT INTO catalog_barcode(item_id, barcode, position) VALUES (:id, :c, :p)").param("id", id).param("c", c).param("p", pos++).update();
    }

    public boolean inUse(long id) {
        return db.sql("SELECT count(*) FROM movement_line WHERE item_id = :id").param("id", id).query(Long.class).single() > 0;
    }

    public void delete(long id) { db.sql("DELETE FROM catalog_item WHERE id = :id").param("id", id).update(); }

    public void setCost(long id, BigDecimal cost) {
        db.sql("UPDATE catalog_item SET cost = :c WHERE id = :id").param("c", cost).param("id", id).update();
    }
}
