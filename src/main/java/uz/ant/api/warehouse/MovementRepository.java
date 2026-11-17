package uz.ant.api.warehouse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.ant.api.common.Time;

@Repository
public class MovementRepository {
    private static final String COLS = "m.id, m.number, m.type, m.date::text AS date, m.warehouse_id, m.to_warehouse_id, m.supplier_id, m.counterparty, m.reason, m.note, m.status, "
            + "m.created_by_id, m.created_by_name, m.created_at, m.cancelled_by_id, m.cancelled_by_name, m.cancelled_at, m.cancel_reason";
    private final JdbcClient db;

    public MovementRepository(JdbcClient db) { this.db = db; }

    public record Filter(String type, String status, long warehouseId, long itemId, LocalDate from, LocalDate to, String q) {}

    private static MovementView map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Long cancelledBy = (Long) rs.getObject("cancelled_by_id");
        Instant cancelledAt = rs.getTimestamp("cancelled_at") == null ? null : rs.getTimestamp("cancelled_at").toInstant();
        return new MovementView(rs.getLong("id"), rs.getString("number"), rs.getString("type"), rs.getString("date"), rs.getLong("warehouse_id"),
                (Long) rs.getObject("to_warehouse_id"), (Long) rs.getObject("supplier_id"), rs.getString("counterparty"), rs.getString("reason"), List.of(),
                rs.getString("note"), rs.getString("status"), new MovementView.Ref(rs.getLong("created_by_id"), rs.getString("created_by_name")),
                Time.iso(rs.getTimestamp("created_at").toInstant()),
                cancelledBy == null ? null : new MovementView.Ref(cancelledBy, rs.getString("cancelled_by_name")),
                Time.iso(cancelledAt), rs.getString("cancel_reason"));
    }

    private List<MovementView> withLines(List<MovementView> rows) {
        if (rows.isEmpty()) return rows;
        Map<Long, List<MovementView.LineView>> lines = new HashMap<>();
        db.sql("SELECT movement_id, item_id, qty, price FROM movement_line WHERE movement_id IN (:ids) ORDER BY movement_id, line_no")
                .param("ids", rows.stream().map(MovementView::id).toList())
                .query((rs, i) -> {
                    lines.computeIfAbsent(rs.getLong("movement_id"), k -> new ArrayList<>())
                            .add(new MovementView.LineView(rs.getLong("item_id"), rs.getBigDecimal("qty"), rs.getBigDecimal("price")));
                    return 0;
                }).list();
        return rows.stream().map(m -> new MovementView(m.id(), m.number(), m.type(), m.date(), m.warehouseId(), m.toWarehouseId(), m.supplierId(), m.counterparty(),
                m.reason(), lines.getOrDefault(m.id(), List.of()), m.note(), m.status(), m.createdBy(), m.createdAt(), m.cancelledBy(), m.cancelledAt(), m.cancelReason())).toList();
    }

    public Optional<MovementView> find(long id) {
        return withLines(db.sql("SELECT " + COLS + " FROM movement m WHERE m.id = :id").param("id", id).query(MovementRepository::map).list()).stream().findFirst();
    }

    private static String like(String q) {
        return "%" + q.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    public record Page(long total, List<MovementView> rows) {}

    public Page search(Filter f, int limit, int offset) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        Map<String, Object> p = new HashMap<>();
        if (f.type() != null) { where.append(" AND m.type = :type"); p.put("type", f.type()); }
        if (f.status() != null) { where.append(" AND m.status = :status"); p.put("status", f.status()); }
        if (f.warehouseId() != 0) { where.append(" AND (m.warehouse_id = :wh OR m.to_warehouse_id = :wh)"); p.put("wh", f.warehouseId()); }
        if (f.itemId() != 0) { where.append(" AND EXISTS (SELECT 1 FROM movement_line l WHERE l.movement_id = m.id AND l.item_id = :item)"); p.put("item", f.itemId()); }
        if (f.from() != null) { where.append(" AND m.date >= :from"); p.put("from", f.from()); }
        if (f.to() != null) { where.append(" AND m.date <= :to"); p.put("to", f.to()); }
        if (f.q() != null && !f.q().isEmpty()) {
            where.append(" AND (lower(m.number) LIKE :q OR lower(m.counterparty) LIKE :q OR lower(m.note) LIKE :q OR lower(m.created_by_name) LIKE :q)");
            p.put("q", like(f.q()));
        }
        long total = db.sql("SELECT count(*) FROM movement m" + where).params(p).query(Long.class).single();
        p.put("limit", limit);
        p.put("offset", offset);
        List<MovementView> rows = db.sql("SELECT " + COLS + " FROM movement m" + where + " ORDER BY m.id DESC LIMIT :limit OFFSET :offset").params(p)
                .query(MovementRepository::map).list();
        return new Page(total, withLines(rows));
    }

    public long insert(String number, String type, LocalDate date, long warehouseId, Long toWarehouseId, Long supplierId, String counterparty, String reason,
                       String note, long byId, String byName, List<StockService.Line> lines) {
        long id = db.sql("""
                INSERT INTO movement(number, type, date, warehouse_id, to_warehouse_id, supplier_id, counterparty, reason, note, status, created_by_id, created_by_name)
                VALUES (:number, :type, :date, :wh, :to, :sup, :cp, :reason, :note, 'posted', :bid, :bname) RETURNING id""")
                .param("number", number).param("type", type).param("date", date).param("wh", warehouseId).param("to", toWarehouseId).param("sup", supplierId)
                .param("cp", counterparty).param("reason", reason).param("note", note).param("bid", byId).param("bname", byName).query(Long.class).single();
        int no = 1;
        for (StockService.Line l : lines) {
            db.sql("INSERT INTO movement_line(movement_id, line_no, item_id, qty, price) VALUES (:m, :n, :i, :q, :p)")
                    .param("m", id).param("n", no++).param("i", l.itemId()).param("q", l.qty()).param("p", l.price()).update();
        }
        return id;
    }

    public void cancel(long id, long byId, String byName, String reason) {
        db.sql("UPDATE movement SET status = 'cancelled', cancelled_by_id = :b, cancelled_by_name = :n, cancelled_at = now(), cancel_reason = :r WHERE id = :id")
                .param("b", byId).param("n", byName).param("r", reason).param("id", id).update();
    }

    /** Tranzaksiya oxirigacha ombor harakatlarini ketma-ket bajarish uchun qulf (qoldiq tekshiruvi poygasiz bo'lsin). */
    public void lockStock() {
        db.sql("SELECT pg_advisory_xact_lock(7001)").query().singleValue();
    }

    public record Totals(BigDecimal value) {}
}
