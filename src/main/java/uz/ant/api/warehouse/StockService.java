package uz.ant.api.warehouse;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Qoldiq hech qachon saqlanmaydi — faqat tasdiqlangan (bekor qilinmagan) hujjatlardan hisoblanadi
 * (`stock` ko'rinishi, V1__init.sql). Bu yerda: o'qish, harakatning qoldiqqa ta'siri va manfiy qoldiq tekshiruvi.
 */
@Service
public class StockService {
    public record Row(long itemId, long warehouseId, BigDecimal qty) {}
    public record Effect(long itemId, long warehouseId, BigDecimal delta) {}
    public record Line(long itemId, BigDecimal qty, BigDecimal price) {}

    private final JdbcClient db;

    public StockService(JdbcClient db) { this.db = db; }

    /** Noldan farqli barcha (tovar, ombor) qoldiqlari. */
    public List<Row> nonZero() {
        return db.sql("SELECT item_id, warehouse_id, qty FROM stock WHERE qty <> 0 ORDER BY item_id, warehouse_id")
                .query((rs, i) -> new Row(rs.getLong("item_id"), rs.getLong("warehouse_id"), rs.getBigDecimal("qty"))).list();
    }

    public BigDecimal qtyOf(long itemId, long warehouseId) {
        return db.sql("SELECT COALESCE(SUM(qty), 0) FROM stock WHERE item_id = :i AND warehouse_id = :w").param("i", itemId).param("w", warehouseId)
                .query(BigDecimal.class).single();
    }

    /** Harakatning har bir tovar/ombor bo'yicha qoldiqqa ta'siri. */
    public static List<Effect> effectsOf(String type, long warehouseId, Long toWarehouseId, List<Line> lines) {
        List<Effect> out = new ArrayList<>();
        for (Line l : lines) {
            switch (type) {
                case "RECEIPT", "ADJUSTMENT" -> out.add(new Effect(l.itemId(), warehouseId, l.qty()));
                case "TRANSFER" -> {
                    out.add(new Effect(l.itemId(), warehouseId, l.qty().negate()));
                    out.add(new Effect(l.itemId(), toWarehouseId, l.qty()));
                }
                default -> out.add(new Effect(l.itemId(), warehouseId, l.qty().negate())); // ISSUE, WRITE_OFF
            }
        }
        return out;
    }

    /** Effektlar `sign` bilan qo'llanilgach manfiy qoldiq paydo bo'lsa — birinchi muammoli effekt. */
    public Effect firstNegative(List<Effect> effects, int sign) {
        Map<String, BigDecimal> next = new LinkedHashMap<>();
        for (Effect e : effects) {
            String key = e.itemId() + ":" + e.warehouseId();
            BigDecimal base = next.containsKey(key) ? next.get(key) : qtyOf(e.itemId(), e.warehouseId());
            next.put(key, base.add(sign > 0 ? e.delta() : e.delta().negate()));
        }
        for (Effect e : effects) if (next.get(e.itemId() + ":" + e.warehouseId()).signum() < 0) return e;
        return null;
    }

    /** Tovar kartochkasi uchun: oxirgi 50 harakat. */
    public List<Map<String, Object>> history(long itemId) {
        record H(long id, String number, String type, String date, String status, long warehouseId, Long toWarehouseId, BigDecimal qty, BigDecimal price, long byId, String byName) {}
        List<H> rows = db.sql("""
                SELECT m.id, m.number, m.type, m.date::text AS date, m.status, m.warehouse_id, m.to_warehouse_id, l.qty, l.price, m.created_by_id, m.created_by_name
                  FROM movement m JOIN movement_line l ON l.movement_id = m.id WHERE l.item_id = :i ORDER BY m.id DESC LIMIT 50""")
                .param("i", itemId).query((rs, n) -> new H(rs.getLong("id"), rs.getString("number"), rs.getString("type"), rs.getString("date"), rs.getString("status"),
                        rs.getLong("warehouse_id"), (Long) rs.getObject("to_warehouse_id"), rs.getBigDecimal("qty"), rs.getBigDecimal("price"),
                        rs.getLong("created_by_id"), rs.getString("created_by_name"))).list();
        List<Map<String, Object>> out = new ArrayList<>();
        for (H h : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", h.id()); m.put("number", h.number()); m.put("type", h.type()); m.put("date", h.date()); m.put("status", h.status());
            m.put("warehouseId", h.warehouseId());
            if (h.toWarehouseId() != null) m.put("toWarehouseId", h.toWarehouseId());
            m.put("qty", h.qty()); m.put("price", h.price());
            m.put("createdBy", Map.of("id", h.byId(), "name", h.byName()));
            m.put("effects", effectsOf(h.type(), h.warehouseId(), h.toWarehouseId(), List.of(new Line(itemId, h.qty(), h.price()))).stream()
                    .map(e -> Map.of("itemId", e.itemId(), "warehouseId", e.warehouseId(), "delta", e.delta())).toList());
            out.add(m);
        }
        return out;
    }
}
