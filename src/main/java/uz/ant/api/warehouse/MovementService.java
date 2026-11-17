package uz.ant.api.warehouse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import uz.ant.api.common.ApiException;
import uz.ant.api.common.Body;
import uz.ant.api.common.Counters;
import uz.ant.api.common.Time;
import uz.ant.api.security.Staff;

/**
 * Ombor hujjatlarini tekshiradi va o'tkazadi (qoldiqlar o'zgaradi). Domen xatolari kodli: `invalid_qty`, `insufficient_stock` va h.k.
 * Xato hujjat o'chirilmaydi — sabab bilan bekor qilinadi.
 */
@Service
public class MovementService {
    private static final java.util.Map<String, String> PREFIX = java.util.Map.of("RECEIPT", "KR", "ISSUE", "CH", "TRANSFER", "KO", "WRITE_OFF", "HC", "ADJUSTMENT", "IN");
    private static final Pattern DATE_RE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private final MovementRepository repo;
    private final CatalogRepository catalog;
    private final RefRepository refs;
    private final StockService stock;
    private final Counters counters;
    private final Time time;

    public MovementService(MovementRepository repo, CatalogRepository catalog, RefRepository refs, StockService stock, Counters counters, Time time) {
        this.repo = repo;
        this.catalog = catalog;
        this.refs = refs;
        this.stock = stock;
        this.counters = counters;
        this.time = time;
    }

    private static ApiException err(String code) { return ApiException.badRequest(code); }

    /** Butun son bo'lgan JSON qiymat (JS `===` bilan ID solishtirish kabi), aks holda null. */
    private static Long idOf(JsonNode v) {
        if (v == null || !v.isNumber()) return null;
        BigDecimal d = v.decimalValue();
        return d.stripTrailingZeros().scale() <= 0 ? d.longValue() : null;
    }

    private static boolean truthy(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) return false;
        if (v.isNumber()) return v.decimalValue().signum() != 0;
        if (v.isString()) return !v.asString().isEmpty();
        if (v.isBoolean()) return v.asBoolean();
        return true;
    }

    private static String str(JsonNode v, int max) { return v != null && v.isString() ? Body.clip(v.asString().trim(), max) : ""; }

    @Transactional
    public MovementView post(JsonNode input, Staff staff) {
        repo.lockStock();
        String type = input.path("type").isString() ? input.get("type").asString() : "";
        if (!PREFIX.containsKey(type)) throw err("invalid_type");

        Long warehouseId = idOf(input.get("warehouseId"));
        if (warehouseId == null || !refs.warehouseActive(warehouseId)) throw err("warehouse_not_found");
        Long toWarehouseId = null;
        if (type.equals("TRANSFER")) {
            Long to = idOf(input.get("toWarehouseId"));
            if (to == null || !refs.warehouseActive(to) || to.equals(warehouseId)) throw err("invalid_destination");
            toWarehouseId = to;
        }
        Long supplierId = null;
        if (type.equals("RECEIPT") && truthy(input.get("supplierId"))) {
            Long sid = idOf(input.get("supplierId"));
            if (sid == null || !refs.supplierExists(sid)) throw err("supplier_not_found");
            supplierId = sid;
        }

        JsonNode raw = input.get("lines");
        if (raw == null || !raw.isArray() || raw.isEmpty() || raw.size() > 200) throw err("invalid_lines");
        List<StockService.Line> lines = new ArrayList<>();
        for (JsonNode l : raw) {
            Long itemId = idOf(l.get("itemId"));
            CatalogRepository.Item item = itemId == null ? null : catalog.findById(itemId).filter(CatalogRepository.Item::active).orElse(null);
            if (item == null) throw new ApiException(HttpStatus.BAD_REQUEST, "item_not_found", l.get("itemId"));
            BigDecimal rawQty = Money.numberOrNull(l.get("qty"));
            BigDecimal qty = rawQty == null ? null : Money.qty(rawQty);
            if (qty == null || qty.signum() == 0 || (!type.equals("ADJUSTMENT") && qty.signum() < 0)) throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_qty", item.id());
            BigDecimal given = Money.numberOrNull(l.get("price"));
            BigDecimal price = type.equals("RECEIPT") ? given : given != null ? given : item.cost();
            if (price == null || price.signum() < 0) throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_price", item.id());
            lines.add(new StockService.Line(item.id(), qty, price));
        }
        if (lines.stream().map(StockService.Line::itemId).collect(java.util.stream.Collectors.toSet()).size() != lines.size()) throw err("duplicate_items");

        var bad = stock.firstNegative(StockService.effectsOf(type, warehouseId, toWarehouseId, lines), 1);
        if (bad != null) throw new ApiException(HttpStatus.CONFLICT, "insufficient_stock", java.util.Map.of("itemId", bad.itemId(), "warehouseId", bad.warehouseId()));

        long n = counters.next("doc:" + type);
        String number = PREFIX.get(type) + "-" + String.format("%05d", n);
        JsonNode d = input.get("date");
        LocalDate date = time.today();
        if (d != null && d.isString() && DATE_RE.matcher(d.asString()).matches()) {
            try {
                date = LocalDate.parse(d.asString());
            } catch (java.time.DateTimeException ignored) {
                // noto'g'ri sana — bugungi kun
            }
        }
        long id = repo.insert(number, type, date, warehouseId, toWarehouseId, supplierId, str(input.get("counterparty"), 160), str(input.get("reason"), 40),
                str(input.get("note"), 500), staff.id(), staff.displayName(), lines);
        // Kirimda tannarx oxirgi sotib olish narxiga yangilanadi
        if (type.equals("RECEIPT")) for (StockService.Line l : lines) if (l.price().signum() > 0) catalog.setCost(l.itemId(), l.price());
        return repo.find(id).orElseThrow();
    }

    /** Harakatni bekor qiladi (qoldiq manfiy bo'lib qolmasa). */
    @Transactional
    public MovementView cancel(long id, String reason, Staff staff) {
        repo.lockStock();
        MovementView m = repo.find(id).orElseThrow(ApiException::notFound);
        if (!m.status().equals("posted")) throw new ApiException(HttpStatus.CONFLICT, "already_cancelled");
        List<StockService.Line> lines = m.lines().stream().map(l -> new StockService.Line(l.itemId(), l.qty(), l.price())).toList();
        var bad = stock.firstNegative(StockService.effectsOf(m.type(), m.warehouseId(), m.toWarehouseId(), lines), -1);
        if (bad != null) throw new ApiException(HttpStatus.CONFLICT, "cancel_would_go_negative", java.util.Map.of("itemId", bad.itemId(), "warehouseId", bad.warehouseId()));
        repo.cancel(id, staff.id(), staff.displayName(), Body.clip(reason, 300));
        return repo.find(id).orElseThrow();
    }

    public static Set<String> types() { return new HashSet<>(PREFIX.keySet()); }
}
