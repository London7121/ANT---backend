package uz.ant.api.stats;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import tools.jackson.databind.node.ObjectNode;
import uz.ant.api.account.SiteUser;
import uz.ant.api.account.SiteUserRepository;
import uz.ant.api.common.Time;
import uz.ant.api.content.ContentRepository;
import uz.ant.api.content.MessageRepository;
import uz.ant.api.finance.FinanceService;
import uz.ant.api.security.AuthInterceptor;
import uz.ant.api.security.Permissions;
import uz.ant.api.security.RequirePermission;
import uz.ant.api.security.Staff;
import uz.ant.api.warehouse.CatalogRepository;
import uz.ant.api.warehouse.MovementRepository;
import uz.ant.api.warehouse.Money;

/** Admin dashboard statistikasi (ruxsatga qarab bo'limlar to'ldiriladi). */
@RestController
@RequestMapping("/api/admin/stats")
@SecurityRequirement(name = "bearer")
@Tag(name = "Admin: dashboard")
public class StatsController {
    private static final int DAYS = 14;
    private final JdbcClient db;
    private final ContentRepository content;
    private final MessageRepository messages;
    private final SiteUserRepository users;
    private final CatalogRepository catalog;
    private final MovementRepository movements;
    private final FinanceService finance;
    private final Time time;

    public StatsController(JdbcClient db, ContentRepository content, MessageRepository messages, SiteUserRepository users, CatalogRepository catalog,
                           MovementRepository movements, FinanceService finance, Time time) {
        this.db = db;
        this.content = content;
        this.messages = messages;
        this.users = users;
        this.catalog = catalog;
        this.movements = movements;
        this.finance = finance;
        this.time = time;
    }

    private long count(String sql) { return db.sql(sql).query(Long.class).single(); }

    private LocalDate dayOf(Instant t) { return t.atZone(time.zone()).toLocalDate(); }

    private static Instant parse(String iso) {
        try {
            return iso == null || iso.isEmpty() ? null : Instant.parse(iso);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /** Oxirgi N kun uchun kunlik hisoblagich. */
    private static List<Long> series(List<Instant> dates, List<LocalDate> days, java.util.function.Function<Instant, LocalDate> toDay) {
        Map<LocalDate, Long> c = new HashMap<>();
        for (Instant t : dates) if (t != null) c.merge(toDay.apply(t), 1L, Long::sum);
        return days.stream().map(d -> c.getOrDefault(d, 0L)).toList();
    }

    @GetMapping
    @RequirePermission("dashboard.view")
    @Operation(summary = "Dashboard statistikasi")
    public Map<String, Object> stats(@RequestAttribute(AuthInterceptor.STAFF) Staff staff) {
        LocalDate today = time.today();
        List<LocalDate> days = new ArrayList<>();
        for (int i = DAYS - 1; i >= 0; i--) days.add(today.minusDays(i));

        List<ObjectNode> reviews = content.list("reviews");
        List<ObjectNode> visibleReviews = reviews.stream().filter(ContentRepository::visible).toList();
        List<Integer> distribution = new ArrayList<>();
        for (int n = 1; n <= 5; n++) {
            final int star = n;
            distribution.add((int) visibleReviews.stream().filter(r -> r.path("rating").asInt(0) == star).count());
        }
        double avg = visibleReviews.isEmpty() ? 0 : visibleReviews.stream().mapToDouble(r -> r.path("rating").asDouble(0)).sum() / visibleReviews.size();

        List<ObjectNode> products = content.list("products");
        List<Map<String, Object>> topFavorites = new ArrayList<>();
        db.sql("SELECT product_id, count(*) AS n FROM user_favorite GROUP BY product_id ORDER BY n DESC, product_id LIMIT 50").query((rs, i) -> {
            long id = rs.getLong("product_id"), n = rs.getLong("n");
            products.stream().filter(p -> p.get("id").asLong() == id).findFirst().ifPresent(p -> {
                if (topFavorites.size() < 5) topFavorites.add(Map.of("id", id, "name", p.path("name"), "count", n));
            });
            return 0;
        }).list();

        Map<String, Object> warehouse = null;
        FinanceService.Valuation valuation = Permissions.can(staff, "stock.view") ? finance.stockValuation() : null;
        if (valuation != null) {
            Map<Long, CatalogRepository.Item> byId = new HashMap<>();
            for (var it : catalog.findAll()) byId.put(it.id(), it);
            warehouse = new LinkedHashMap<>();
            warehouse.put("items", count("SELECT count(*) FROM catalog_item WHERE active"));
            warehouse.put("warehouses", count("SELECT count(*) FROM warehouse WHERE active"));
            warehouse.put("movementsToday", db.sql("SELECT count(*) FROM movement WHERE status = 'posted' AND date = :d").param("d", today).query(Long.class).single());
            warehouse.put("lowStock", valuation.low().stream().limit(6).map(l -> {
                var it = byId.get(l.itemId());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("itemId", l.itemId());
                m.put("qty", l.qty());
                m.put("minStock", l.minStock());
                m.put("name", it == null ? "" : it.name());
                m.put("unit", it == null ? "pcs" : it.unit());
                return m;
            }).toList());
            warehouse.put("lowStockCount", valuation.low().size());
            warehouse.put("stockValue", Permissions.can(staff, "reports.view") ? valuation.totalValue() : null);
            warehouse.put("recent", Permissions.can(staff, "movements.view") ? movements.search(new MovementRepository.Filter(null, null, 0, 0, null, null, ""), 6, 0).rows().stream().map(m -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", m.id()); r.put("number", m.number()); r.put("type", m.type()); r.put("status", m.status()); r.put("date", m.date());
                r.put("createdBy", m.createdBy()); r.put("value", m.value());
                return r;
            }).toList() : List.of());
        }

        Map<String, Object> fin = null;
        if (Permissions.can(staff, "finance.view")) {
            LocalDate monthStart = today.withDayOfMonth(1);
            fin = new LinkedHashMap<>();
            fin.put("incomeMonth", sum("income", monthStart, today));
            fin.put("expenseMonth", sum("expense", monthStart, today));
            fin.put("payablesTotal", finance.payablesTotal());
        }

        List<SiteUser> siteUsers = users.findAll();
        List<Map<String, Object>> allMessages = messages.list();
        List<Instant> messageDates = allMessages.stream().map(m -> parse((String) m.get("createdAt"))).toList();

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("products", products.size());
        totals.put("productsHidden", products.stream().filter(p -> p.has("visible") && p.get("visible").isBoolean() && !p.get("visible").asBoolean()).count());
        totals.put("posts", count("SELECT count(*) FROM content_item WHERE collection = 'posts'"));
        totals.put("news", count("SELECT count(*) FROM content_item WHERE collection = 'news'"));
        totals.put("reviews", reviews.size());
        totals.put("reviewsHidden", reviews.size() - visibleReviews.size());
        totals.put("users", siteUsers.size());
        totals.put("usersBlocked", siteUsers.stream().filter(SiteUser::blocked).count());
        totals.put("messages", allMessages.size());
        totals.put("messagesUnread", allMessages.stream().filter(m -> !(Boolean) m.get("read")).count());
        totals.put("media", count("SELECT count(*) FROM media"));
        totals.put("mediaBytes", count("SELECT COALESCE(SUM(size), 0) FROM media"));
        totals.put("favorites", count("SELECT count(*) FROM user_favorite"));

        Map<String, Object> rating = new LinkedHashMap<>();
        rating.put("average", Math.floor(avg * 10 + 0.5) / 10);
        rating.put("count", visibleReviews.size());
        rating.put("distribution", distribution);

        Map<String, Object> series = new LinkedHashMap<>();
        series.put("days", days.stream().map(LocalDate::toString).toList());
        series.put("messages", series(messageDates, days, this::dayOf));
        series.put("users", series(siteUsers.stream().map(SiteUser::createdAt).toList(), days, this::dayOf));
        series.put("reviews", series(reviews.stream().map(r -> parse(r.path("createdAt").asString(null))).toList(), days, this::dayOf));

        Map<String, Object> recent = new LinkedHashMap<>();
        recent.put("messages", allMessages.stream().limit(5).toList());
        recent.put("reviews", reviews.stream().sorted(Comparator.comparing((ObjectNode r) -> r.path("createdAt").asString("")).reversed()).limit(5).toList());
        recent.put("users", siteUsers.stream().sorted(Comparator.comparing(SiteUser::createdAt).reversed()).limit(5)
                .map(u -> Map.of("id", u.id(), "username", u.username(), "createdAt", Time.iso(u.createdAt()))).toList());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("warehouse", warehouse);
        out.put("finance", fin);
        out.put("totals", totals);
        out.put("rating", rating);
        out.put("series", series);
        out.put("topFavorites", topFavorites);
        out.put("recent", recent);
        return out;
    }

    private BigDecimal sum(String type, LocalDate from, LocalDate to) {
        return Money.money(db.sql("SELECT COALESCE(SUM(amount), 0) FROM ledger_transaction WHERE type = :t AND date BETWEEN :f AND :to").param("t", type).param("f", from).param("to", to)
                .query(BigDecimal.class).single());
    }

}
