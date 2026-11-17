package uz.ant.api.warehouse;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
import uz.ant.api.security.RequirePermission;

/** Tovar katalogi + har bir ombordagi qoldiq (hujjatlardan hisoblanadi, qo'lda o'zgartirilmaydi). */
@RestController
@RequestMapping("/api/admin/catalog")
@SecurityRequirement(name = "bearer")
@Tag(name = "Ombor: tovar katalogi")
public class CatalogController {
    private static final Pattern BARCODE = Pattern.compile("^[A-Za-z0-9-]{4,32}$");
    private static final List<String> UNITS = List.of("pcs", "kg", "g", "l", "m", "box", "pack");
    private final CatalogRepository repo;
    private final StockService stock;

    public CatalogController(CatalogRepository repo, StockService stock) {
        this.repo = repo;
        this.stock = stock;
    }

    private static BigDecimal money(Body b, String key) {
        BigDecimal v = Money.numberOrNull(b.get(key));
        return v == null || v.signum() < 0 ? BigDecimal.ZERO : Money.money(v);
    }

    /** Tanani tozalaydi; SKU/shtrix kod takrorlanishi alohida (bazaga bog'liq) tekshiriladi. */
    private CatalogRepository.Data parse(JsonNode raw, long selfId) {
        Body b = Body.of(raw);
        String sku = RefController.required(b, "sku", 40);
        String name = RefController.required(b, "name", 160);
        String unit = b.get("unit") != null && b.get("unit").isString() && UNITS.contains(b.get("unit").asString()) ? b.get("unit").asString() : "pcs";
        List<String> codes = new ArrayList<>();
        JsonNode arr = b.get("barcodes");
        if (arr != null && arr.isArray() && arr.valueStream().allMatch(JsonNode::isString)) {
            LinkedHashSet<String> set = new LinkedHashSet<>();
            for (JsonNode n : arr) {
                String c = Body.clip(n.asString().trim(), 32);
                if (!c.isEmpty()) set.add(c);
            }
            codes.addAll(set);
        }
        for (String c : codes) if (!BARCODE.matcher(c).matches()) throw ApiException.badRequest("invalid_barcode");
        if (repo.skuTaken(sku, selfId)) throw ApiException.conflict("sku_taken");
        if (repo.barcodeTaken(codes, selfId)) throw ApiException.conflict("barcode_taken");
        return new CatalogRepository.Data(sku, name, b.text("category", 60), unit, codes, money(b, "cost"), money(b, "price"), money(b, "minStock"),
                b.text("image", 300), b.text("note", 500), b.flag("active", true));
    }

    @GetMapping
    @RequirePermission("catalog.view")
    @Operation(summary = "Katalog + har bir ombordagi qoldiq", description = "`stock`: ombor ID → qoldiq (nolsiz), `stockTotal`: jami.")
    public List<Map<String, Object>> list() {
        Map<Long, Map<String, BigDecimal>> byItem = new java.util.HashMap<>();
        Map<Long, BigDecimal> totals = new java.util.HashMap<>();
        for (var r : stock.nonZero()) {
            byItem.computeIfAbsent(r.itemId(), k -> new LinkedHashMap<>()).put(String.valueOf(r.warehouseId()), r.qty());
            totals.merge(r.itemId(), r.qty(), BigDecimal::add);
        }
        return repo.findAll().stream().map(it -> {
            Map<String, Object> m = it.toJson();
            m.put("stock", byItem.getOrDefault(it.id(), Map.of()));
            m.put("stockTotal", Money.qty(totals.getOrDefault(it.id(), BigDecimal.ZERO)));
            return m;
        }).toList();
    }

    /** Shtrix kod yoki SKU bo'yicha tez qidirish (skaner uchun). */
    @GetMapping("/lookup")
    @RequirePermission("catalog.view")
    @Operation(summary = "Shtrix kod yoki SKU bo'yicha qidirish", description = "Topilmasa 404 `not_found`.")
    public Map<String, Object> lookup(@RequestParam(required = false) String code) {
        String c = code == null ? "" : Body.clip(code.trim(), 40).toLowerCase();
        if (c.isEmpty()) throw ApiException.notFound();
        return repo.findByCode(c).orElseThrow(ApiException::notFound).toJson();
    }

    /** Tovar kartochkasi: shu tovar bo'yicha oxirgi 50 harakat. */
    @GetMapping("/{id}/history")
    @RequirePermission("catalog.view")
    @Operation(summary = "Tovar bo'yicha oxirgi 50 harakat")
    public List<Map<String, Object>> history(@PathVariable long id) {
        return stock.history(id);
    }

    @PostMapping
    @RequirePermission("catalog.create")
    @Operation(summary = "Tovar qo'shish", description = "Takroriy SKU — 409 `sku_taken`, takroriy shtrix kod — 409 `barcode_taken`, noto'g'ri shtrix kod — 400 `invalid_barcode`.")
    public Map<String, Object> create(@RequestBody(required = false) JsonNode raw) {
        try {
            return repo.insert(parse(raw, 0)).toJson();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("sku_taken");
        }
    }

    @PutMapping("/{id}")
    @RequirePermission("catalog.edit")
    @Transactional
    @Operation(summary = "Tovarni tahrirlash")
    public Map<String, Object> update(@PathVariable long id, @RequestBody(required = false) JsonNode raw) {
        repo.findById(id).orElseThrow(ApiException::notFound);
        try {
            return repo.update(id, parse(raw, id)).toJson();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("sku_taken");
        }
    }

    @DeleteMapping("/{id}")
    @RequirePermission("catalog.delete")
    @Operation(summary = "Tovarni o'chirish", description = "Hujjatlarda ishlatilgan bo'lsa — 409 `in_use`.")
    public Map<String, Object> delete(@PathVariable long id) {
        if (repo.inUse(id)) throw new ApiException(HttpStatus.CONFLICT, "in_use");
        repo.delete(id);
        return Map.of("ok", true);
    }
}
