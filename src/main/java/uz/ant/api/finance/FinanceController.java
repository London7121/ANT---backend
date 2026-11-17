package uz.ant.api.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import tools.jackson.databind.JsonNode;
import uz.ant.api.common.ApiException;
import uz.ant.api.common.Body;
import uz.ant.api.common.Time;
import uz.ant.api.security.AuthInterceptor;
import uz.ant.api.security.RequirePermission;
import uz.ant.api.security.Staff;
import uz.ant.api.warehouse.Money;
import uz.ant.api.warehouse.RefRepository;

/** Buxgalteriya: kassa operatsiyalari, qarzdorlik, hisobotlar. */
@RestController
@RequestMapping("/api/admin")
@SecurityRequirement(name = "bearer")
@Tag(name = "Buxgalteriya")
public class FinanceController {
    private static final List<String> INCOME = List.of("sales", "other_income");
    private static final List<String> EXPENSE = List.of("supplier_payment", "salary", "rent", "utilities", "tax", "marketing", "other_expense");
    private static final List<String> METHODS = List.of("cash", "card", "transfer");
    private static final Pattern DATE_RE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final String COLS = "id, date::text AS date, type, category, amount, method, counterparty, supplier_id, note, created_by_id, created_by_name, created_at";

    private final JdbcClient db;
    private final FinanceService finance;
    private final RefRepository refs;
    private final Time time;

    public FinanceController(JdbcClient db, FinanceService finance, RefRepository refs, Time time) {
        this.db = db;
        this.finance = finance;
        this.refs = refs;
        this.time = time;
    }

    private static Map<String, Object> map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("date", rs.getString("date"));
        m.put("type", rs.getString("type"));
        m.put("category", rs.getString("category"));
        m.put("amount", rs.getBigDecimal("amount"));
        m.put("method", rs.getString("method"));
        m.put("counterparty", rs.getString("counterparty"));
        if (rs.getObject("supplier_id") != null) m.put("supplierId", rs.getLong("supplier_id"));
        m.put("note", rs.getString("note"));
        m.put("createdBy", Map.of("id", rs.getLong("created_by_id"), "name", rs.getString("created_by_name")));
        m.put("createdAt", Time.iso(rs.getTimestamp("created_at").toInstant()));
        return m;
    }

    private Map<String, Object> find(long id) {
        return db.sql("SELECT " + COLS + " FROM ledger_transaction WHERE id = :id").param("id", id).query(FinanceController::map).optional().orElseThrow(ApiException::notFound);
    }

    private record Data(String type, String category, BigDecimal amount, LocalDate date, String method, String counterparty, Long supplierId, String note) {}

    /** Tanani tozalaydi; yetkazib beruvchi bazada borligi shu yerda tekshiriladi. */
    private Data shape(JsonNode raw) {
        Body b = Body.of(raw);
        String type = b.get("type") != null && b.get("type").isString() ? b.get("type").asString() : "";
        String category = b.get("category") != null && b.get("category").isString() ? b.get("category").asString() : "";
        if (!(type.equals("income") && INCOME.contains(category)) && !(type.equals("expense") && EXPENSE.contains(category))) throw ApiException.badRequest("invalid");
        BigDecimal amount = Money.numberOrNull(b.get("amount"));
        if (amount == null || Money.money(amount).signum() <= 0) throw ApiException.badRequest("invalid");
        LocalDate date = time.today();
        JsonNode d = b.get("date");
        if (d != null && d.isString() && DATE_RE.matcher(d.asString()).matches()) {
            try {
                date = LocalDate.parse(d.asString());
            } catch (java.time.DateTimeException ignored) {
                // noto'g'ri sana — bugungi kun
            }
        }
        String method = b.get("method") != null && b.get("method").isString() && METHODS.contains(b.get("method").asString()) ? b.get("method").asString() : "cash";
        JsonNode sid = b.get("supplierId");
        if (sid != null && !sid.isIntegralNumber()) throw ApiException.badRequest("invalid");
        Long supplierId = null;
        if (category.equals("supplier_payment")) {
            if (sid == null || !refs.supplierExists(sid.asLong())) throw ApiException.badRequest("supplier_required");
            supplierId = sid.asLong();
        }
        return new Data(type, category, Money.money(amount), date, method, b.text("counterparty", 160), supplierId, b.text("note", 500));
    }

    private static String like(String q) { return "%" + q.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"; }

    @GetMapping("/finance/transactions")
    @RequirePermission("finance.view")
    @Operation(summary = "Kassa operatsiyalari", description = "Filtrlar: type, category, from, to (YYYY-MM-DD), q (kimdan/kimga yoki izoh bo'yicha). Sana va ID bo'yicha kamayish tartibida.")
    public List<Map<String, Object>> transactions(@RequestParam(required = false) String type, @RequestParam(required = false) String category,
                                                  @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                                  @RequestParam(required = false) String q) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        Map<String, Object> p = new HashMap<>();
        if (type != null && !type.isEmpty()) { where.append(" AND type = :type"); p.put("type", type); }
        if (category != null && !category.isEmpty()) { where.append(" AND category = :cat"); p.put("cat", category); }
        LocalDate f = Time.parseDate(from), t = Time.parseDate(to);
        if (f != null) { where.append(" AND date >= :from"); p.put("from", f); }
        if (t != null) { where.append(" AND date <= :to"); p.put("to", t); }
        String needle = q == null ? "" : q.trim();
        if (!needle.isEmpty()) { where.append(" AND (lower(counterparty) LIKE :q OR lower(note) LIKE :q)"); p.put("q", like(needle)); }
        return db.sql("SELECT " + COLS + " FROM ledger_transaction" + where + " ORDER BY date DESC, id DESC").params(p).query(FinanceController::map).list();
    }

    @PostMapping("/finance/transactions")
    @RequirePermission("finance.create")
    @Operation(summary = "Kassa operatsiyasi qo'shish", description = "type=income: sales | other_income; type=expense: supplier_payment (supplierId majburiy) | salary | rent | utilities | tax | marketing | other_expense.")
    public Map<String, Object> create(@RequestAttribute(AuthInterceptor.STAFF) Staff staff, @RequestBody(required = false) JsonNode raw) {
        Data d = shape(raw);
        long id = db.sql("INSERT INTO ledger_transaction(date, type, category, amount, method, counterparty, supplier_id, note, created_by_id, created_by_name) VALUES (:date, :type, :cat, :amt, :m, :cp, :sup, :note, :bid, :bn) RETURNING id")
                .param("date", d.date()).param("type", d.type()).param("cat", d.category()).param("amt", d.amount()).param("m", d.method()).param("cp", d.counterparty())
                .param("sup", d.supplierId()).param("note", d.note()).param("bid", staff.id()).param("bn", staff.displayName()).query(Long.class).single();
        return find(id);
    }

    @PutMapping("/finance/transactions/{id}")
    @RequirePermission("finance.edit")
    @Operation(summary = "Kassa operatsiyasini tahrirlash")
    public Map<String, Object> update(@PathVariable long id, @RequestBody(required = false) JsonNode raw) {
        find(id);
        Data d = shape(raw);
        db.sql("UPDATE ledger_transaction SET date = :date, type = :type, category = :cat, amount = :amt, method = :m, counterparty = :cp, supplier_id = :sup, note = :note WHERE id = :id")
                .param("date", d.date()).param("type", d.type()).param("cat", d.category()).param("amt", d.amount()).param("m", d.method()).param("cp", d.counterparty())
                .param("sup", d.supplierId()).param("note", d.note()).param("id", id).update();
        return find(id);
    }

    @DeleteMapping("/finance/transactions/{id}")
    @RequirePermission("finance.delete")
    @Operation(summary = "Kassa operatsiyasini o'chirish")
    public Map<String, Object> delete(@PathVariable long id) {
        db.sql("DELETE FROM ledger_transaction WHERE id = :id").param("id", id).update();
        return Map.of("ok", true);
    }

    @GetMapping("/finance/payables")
    @RequirePermission("finance.view")
    @Operation(summary = "Yetkazib beruvchilar qarzdorligi", description = "balance = olingan tovar qiymati − to'langan summa")
    public List<Map<String, Object>> payables() { return finance.payables(); }

    @GetMapping("/reports")
    @RequirePermission("reports.view")
    @Operation(summary = "Davr hisoboti", description = "from/to (YYYY-MM-DD); berilmasa joriy oy boshidan bugungacha.")
    public Map<String, Object> report(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        LocalDate today = time.today();
        LocalDate f = Time.parseDate(from);
        LocalDate t = Time.parseDate(to);
        return finance.report(f != null && DATE_RE.matcher(from.trim()).matches() ? f : today.withDayOfMonth(1), t != null && DATE_RE.matcher(to.trim()).matches() ? t : today);
    }
}
