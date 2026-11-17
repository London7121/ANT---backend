package uz.ant.api.warehouse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import uz.ant.api.common.ApiException;
import uz.ant.api.common.Body;
import uz.ant.api.common.Json;
import uz.ant.api.security.AuthInterceptor;
import uz.ant.api.security.RequirePermission;
import uz.ant.api.security.Staff;

/** Kirim-chiqim hujjatlari jurnali va inventarizatsiya. */
@RestController
@RequestMapping("/api/admin")
@SecurityRequirement(name = "bearer")
@Tag(name = "Ombor: hujjatlar va inventarizatsiya")
public class MovementController {
    private final MovementRepository repo;
    private final MovementService service;
    private final CatalogRepository catalog;
    private final StockService stock;
    private final Json json;

    public MovementController(MovementRepository repo, MovementService service, CatalogRepository catalog, StockService stock, Json json) {
        this.repo = repo;
        this.service = service;
        this.catalog = catalog;
        this.stock = stock;
        this.json = json;
    }

    private static long num(String s) {
        try {
            return s == null ? 0 : (long) Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @GetMapping("/movements")
    @RequirePermission("movements.view")
    @Operation(summary = "Hujjatlar jurnali", description = "Filtrlar: type, status, warehouseId, itemId, from, to (YYYY-MM-DD), q; sahifalash: limit (≤500, standart 50), offset. Eng yangisi birinchi.")
    public Map<String, Object> list(@RequestParam(required = false) String type, @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String warehouseId, @RequestParam(required = false) String itemId,
                                    @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                    @RequestParam(required = false) String q, @RequestParam(required = false) String limit,
                                    @RequestParam(required = false) String offset) {
        var filter = new MovementRepository.Filter(blankToNull(type), blankToNull(status), num(warehouseId), num(itemId), uz.ant.api.common.Time.parseDate(from), uz.ant.api.common.Time.parseDate(to), q == null ? "" : q.trim());
        long lim = num(limit) == 0 ? 50 : num(limit);
        var page = repo.search(filter, (int) Math.max(1, Math.min(lim, 500)), (int) Math.max(0, num(offset)));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", page.total());
        m.put("rows", page.rows());
        return m;
    }

    private static String blankToNull(String s) { return s == null || s.isEmpty() ? null : s; }

    @PostMapping("/movements")
    @RequirePermission("movements.create")
    @Operation(summary = "Hujjat yaratish va o'tkazish (kirim, chiqim, ko'chirish, hisobdan chiqarish, tuzatish)",
            description = "Xato kodlari: invalid_type, warehouse_not_found, invalid_destination, supplier_not_found, invalid_lines, item_not_found, invalid_qty, invalid_price, duplicate_items, insufficient_stock (409).")
    public MovementView create(@RequestAttribute(AuthInterceptor.STAFF) Staff staff, @RequestBody(required = false) JsonNode body) {
        return service.post(Body.of(body).raw(), staff);
    }

    /** Jiddiy amal: alohida ruxsat — movements.delete. */
    @PostMapping("/movements/{id}/cancel")
    @RequirePermission("movements.delete")
    @Operation(summary = "Hujjatni sabab bilan bekor qilish", description = "Qoldiq manfiy bo'lib qolsa — 409 `cancel_would_go_negative`; allaqachon bekor — 409 `already_cancelled`.")
    public MovementView cancel(@RequestAttribute(AuthInterceptor.STAFF) Staff staff, @PathVariable long id, @RequestBody(required = false) JsonNode body) {
        JsonNode r = Body.of(body).get("reason");
        String reason = r != null && r.isString() ? r.asString().trim() : "";
        if (reason.isEmpty()) throw ApiException.badRequest("reason_required");
        return service.cancel(id, reason, staff);
    }

    /** Sanalgan qoldiq tizim qoldig'idan farq qilsa, tuzatish hujjati (ADJUSTMENT) yaratiladi. */
    @PostMapping("/inventory/apply")
    @RequirePermission("inventory.create")
    @Transactional
    @Operation(summary = "Inventarizatsiya natijasini qo'llash", description = "Farq bo'lmasa — 400 `no_differences`.")
    public MovementView inventory(@RequestAttribute(AuthInterceptor.STAFF) Staff staff, @RequestBody(required = false) JsonNode raw) {
        Body b = Body.of(raw);
        long warehouseId = b.requireLong("warehouseId");
        JsonNode counts = b.get("counts");
        if (counts == null || !counts.isArray()) throw ApiException.badRequest("invalid");
        ArrayNode lines = json.object().arrayNode();
        for (JsonNode c : counts) {
            JsonNode itemId = c.get("itemId"), counted = c.get("counted");
            if (itemId == null || !itemId.isIntegralNumber() || counted == null || !counted.isNumber() || counted.decimalValue().signum() < 0) throw ApiException.badRequest("invalid");
            BigDecimal diff = Money.qty(counted.decimalValue().subtract(stock.qtyOf(itemId.asLong(), warehouseId)));
            if (diff.signum() == 0) continue;
            BigDecimal cost = catalog.findById(itemId.asLong()).map(CatalogRepository.Item::cost).orElse(BigDecimal.ZERO);
            lines.add(json.object().put("itemId", itemId.asLong()).put("qty", diff).put("price", cost));
        }
        if (lines.isEmpty()) throw ApiException.badRequest("no_differences");
        ObjectNode input = json.object().put("type", "ADJUSTMENT").put("warehouseId", warehouseId).put("reason", "inventory");
        input.set("lines", lines);
        input.put("note", b.text("note", 500));
        return service.post(input, staff);
    }

}
