package uz.ant.api.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import uz.ant.api.warehouse.Money;
import uz.ant.api.warehouse.StockService;

/** Hisob-kitoblar: yetkazib beruvchilar qarzi, qoldiq qiymati, davr hisoboti. */
@Service
public class FinanceService {
    private final JdbcClient db;
    private final StockService stock;

    public FinanceService(JdbcClient db, StockService stock) {
        this.db = db;
        this.stock = stock;
    }

    /** Yetkazib beruvchilar qarzdorligi: olingan tovar qiymati − to'langan summa. */
    public List<Map<String, Object>> payables() {
        Map<Long, BigDecimal> purchased = new HashMap<>();
        db.sql("""
                SELECT m.supplier_id, SUM(l.qty * l.price) AS v FROM movement m JOIN movement_line l ON l.movement_id = m.id
                 WHERE m.status = 'posted' AND m.type = 'RECEIPT' AND m.supplier_id IS NOT NULL GROUP BY m.supplier_id""")
                .query((rs, i) -> purchased.put(rs.getLong("supplier_id"), rs.getBigDecimal("v"))).list();
        Map<Long, BigDecimal> paid = new HashMap<>();
        db.sql("SELECT supplier_id, SUM(amount) AS v FROM ledger_transaction WHERE type = 'expense' AND category = 'supplier_payment' AND supplier_id IS NOT NULL GROUP BY supplier_id")
                .query((rs, i) -> paid.put(rs.getLong("supplier_id"), rs.getBigDecimal("v"))).list();
        return db.sql("SELECT id, name, phone, active FROM supplier ORDER BY id").query((rs, i) -> {
            long id = rs.getLong("id");
            BigDecimal p = purchased.getOrDefault(id, BigDecimal.ZERO), d = paid.getOrDefault(id, BigDecimal.ZERO);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("supplierId", id);
            m.put("name", rs.getString("name"));
            m.put("phone", rs.getString("phone"));
            m.put("active", rs.getBoolean("active"));
            m.put("purchased", Money.money(p));
            m.put("paid", Money.money(d));
            m.put("balance", Money.money(p.subtract(d)));
            return m;
        }).list();
    }

    public BigDecimal payablesTotal() {
        return Money.money(payables().stream().map(p -> (BigDecimal) p.get("balance")).filter(b -> b.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    public record Low(long itemId, BigDecimal qty, BigDecimal minStock) {}

    public record Valuation(BigDecimal totalValue, BigDecimal totalQty, List<Low> low, List<Map<String, Object>> byWarehouse, List<Map<String, Object>> byCategory) {}

    /** Qoldiq qiymati (oxirgi tannarx bo'yicha): jami, omborlar va kategoriyalar kesimida + kam qolgan tovarlar. */
    public Valuation stockValuation() {
        record Item(long id, String category, BigDecimal cost, BigDecimal minStock, boolean active) {}
        List<Item> items = db.sql("SELECT id, category, cost, min_stock, active FROM catalog_item ORDER BY id")
                .query((rs, i) -> new Item(rs.getLong("id"), rs.getString("category"), rs.getBigDecimal("cost"), rs.getBigDecimal("min_stock"), rs.getBoolean("active"))).list();
        List<long[]> warehouses = new ArrayList<>();
        Map<Long, String> whNames = new LinkedHashMap<>();
        db.sql("SELECT id, name FROM warehouse ORDER BY id").query((rs, i) -> whNames.put(rs.getLong("id"), rs.getString("name"))).list();
        Map<String, BigDecimal> qtyByKey = new HashMap<>();
        for (var r : stock.nonZero()) qtyByKey.put(r.itemId() + ":" + r.warehouseId(), r.qty());

        Map<Long, BigDecimal> byWh = new HashMap<>();
        Map<String, BigDecimal> byCat = new LinkedHashMap<>();
        BigDecimal totalValue = BigDecimal.ZERO, totalQty = BigDecimal.ZERO;
        List<Low> low = new ArrayList<>();
        for (Item it : items) {
            BigDecimal qty = BigDecimal.ZERO;
            for (Long w : whNames.keySet()) {
                BigDecimal q = qtyByKey.getOrDefault(it.id() + ":" + w, BigDecimal.ZERO);
                qty = qty.add(q);
                byWh.merge(w, q.multiply(it.cost()), BigDecimal::add);
            }
            BigDecimal value = qty.multiply(it.cost());
            totalValue = totalValue.add(value);
            totalQty = totalQty.add(qty);
            byCat.merge(it.category().isEmpty() ? "—" : it.category(), value, BigDecimal::add);
            if (it.active() && it.minStock().signum() > 0 && qty.compareTo(it.minStock()) <= 0) low.add(new Low(it.id(), Money.qty(qty), it.minStock()));
        }
        List<Map<String, Object>> whList = new ArrayList<>();
        for (var e : whNames.entrySet()) whList.add(Map.of("id", e.getKey(), "name", e.getValue(), "value", Money.money(byWh.getOrDefault(e.getKey(), BigDecimal.ZERO))));
        List<Map<String, Object>> catList = byCat.entrySet().stream().map(e -> Map.<String, Object>of("category", e.getKey(), "value", Money.money(e.getValue())))
                .sorted((a, b) -> ((BigDecimal) b.get("value")).compareTo((BigDecimal) a.get("value"))).toList();
        return new Valuation(Money.money(totalValue), Money.qty(totalQty), low, whList, catList);
    }

    private Map<String, BigDecimal> byCategory(LocalDate from, LocalDate to, String type) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        db.sql("SELECT category, SUM(amount) AS v FROM ledger_transaction WHERE type = :t AND date BETWEEN :f AND :to GROUP BY category ORDER BY category")
                .param("t", type).param("f", from).param("to", to).query((rs, i) -> out.put(rs.getString("category"), Money.money(rs.getBigDecimal("v")))).list();
        return out;
    }

    /** Davr hisoboti: kassa, ombor harakatlari, qoldiq qiymati va qarzdorlik. */
    public Map<String, Object> report(LocalDate from, LocalDate to) {
        BigDecimal income = db.sql("SELECT COALESCE(SUM(amount), 0) FROM ledger_transaction WHERE type = 'income' AND date BETWEEN :f AND :t").param("f", from).param("t", to).query(BigDecimal.class).single();
        BigDecimal expense = db.sql("SELECT COALESCE(SUM(amount), 0) FROM ledger_transaction WHERE type = 'expense' AND date BETWEEN :f AND :t").param("f", from).param("t", to).query(BigDecimal.class).single();

        Map<String, Map<String, BigDecimal>> perDay = new HashMap<>();
        db.sql("SELECT date::text AS d, type, SUM(amount) AS v FROM ledger_transaction WHERE date BETWEEN :f AND :t GROUP BY date, type").param("f", from).param("t", to)
                .query((rs, i) -> perDay.computeIfAbsent(rs.getString("d"), k -> new HashMap<>()).put(rs.getString("type"), rs.getBigDecimal("v"))).list();
        List<Map<String, Object>> byDay = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to) && byDay.size() < 400; d = d.plusDays(1)) {
            Map<String, BigDecimal> m = perDay.getOrDefault(d.toString(), Map.of());
            byDay.add(Map.of("date", d.toString(), "income", Money.money(m.getOrDefault("income", BigDecimal.ZERO)), "expense", Money.money(m.getOrDefault("expense", BigDecimal.ZERO))));
        }

        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, BigDecimal> values = new HashMap<>();
        db.sql("""
                SELECT m.type, count(DISTINCT m.id) AS n, COALESCE(SUM(l.qty * l.price), 0) AS v FROM movement m LEFT JOIN movement_line l ON l.movement_id = m.id
                 WHERE m.status = 'posted' AND m.date BETWEEN :f AND :t GROUP BY m.type ORDER BY m.type""").param("f", from).param("t", to)
                .query((rs, i) -> { counts.put(rs.getString("type"), rs.getLong("n")); return values.put(rs.getString("type"), rs.getBigDecimal("v")); }).list();

        Valuation v = stockValuation();
        Map<String, Object> finance = new LinkedHashMap<>();
        finance.put("income", Money.money(income));
        finance.put("expense", Money.money(expense));
        finance.put("net", Money.money(income.subtract(expense)));
        finance.put("incomeByCategory", byCategory(from, to, "income"));
        finance.put("expenseByCategory", byCategory(from, to, "expense"));
        finance.put("byDay", byDay);
        Map<String, Object> movements = new LinkedHashMap<>();
        movements.put("counts", counts);
        movements.put("receiptsValue", Money.money(values.getOrDefault("RECEIPT", BigDecimal.ZERO)));
        movements.put("issuesValue", Money.money(values.getOrDefault("ISSUE", BigDecimal.ZERO)));
        movements.put("writeOffValue", Money.money(values.getOrDefault("WRITE_OFF", BigDecimal.ZERO)));
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("totalValue", v.totalValue());
        st.put("totalQty", v.totalQty());
        st.put("lowStock", v.low().size());
        st.put("byWarehouse", v.byWarehouse());
        st.put("byCategory", v.byCategory());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("period", Map.of("from", from.toString(), "to", to.toString()));
        r.put("finance", finance);
        r.put("movements", movements);
        r.put("stock", st);
        r.put("payables", Map.of("total", payablesTotal()));
        return r;
    }
}
