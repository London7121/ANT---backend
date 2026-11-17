package uz.ant.api.warehouse;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import tools.jackson.databind.JsonNode;
import uz.ant.api.common.ApiException;
import uz.ant.api.common.Body;
import uz.ant.api.security.RequirePermission;

/** Omborlar va yetkazib beruvchilar: ro'yxat, yaratish, tahrirlash, o'chirish. Hujjatlarda ishlatilgan bo'lsa o'chirib bo'lmaydi (409 `in_use`). */
@RestController
@RequestMapping("/api/admin")
@SecurityRequirement(name = "bearer")
@Tag(name = "Ombor: omborlar va yetkazib beruvchilar")
public class RefController {
    private final RefRepository repo;

    public RefController(RefRepository repo) { this.repo = repo; }

    /** Majburiy matn: tozalanadi, kesiladi; bo'sh bo'lsa 400 `invalid`. */
    static String required(Body b, String key, int max) {
        String v = Body.clip(b.requireString(key).trim(), max);
        if (v.isEmpty()) throw ApiException.badRequest("invalid");
        return v;
    }

    // ---------- Omborlar ----------
    @GetMapping("/warehouses")
    @RequirePermission("warehouses.view")
    @Operation(summary = "Omborlar ro'yxati")
    public List<Map<String, Object>> warehouses() { return repo.warehouses(); }

    @PostMapping("/warehouses")
    @RequirePermission("warehouses.create")
    @Operation(summary = "Ombor yaratish")
    public Map<String, Object> createWarehouse(@RequestBody(required = false) JsonNode raw) {
        Body b = Body.of(raw);
        return repo.saveWarehouse(null, required(b, "name", 80), b.text("address", 200), b.text("note", 300), b.flag("active", true));
    }

    @PutMapping("/warehouses/{id}")
    @RequirePermission("warehouses.edit")
    @Operation(summary = "Omborni tahrirlash")
    public Map<String, Object> updateWarehouse(@PathVariable long id, @RequestBody(required = false) JsonNode raw) {
        repo.warehouse(id).orElseThrow(ApiException::notFound);
        Body b = Body.of(raw);
        return repo.saveWarehouse(id, required(b, "name", 80), b.text("address", 200), b.text("note", 300), b.flag("active", true));
    }

    @DeleteMapping("/warehouses/{id}")
    @RequirePermission("warehouses.delete")
    @Operation(summary = "Omborni o'chirish")
    public Map<String, Object> deleteWarehouse(@PathVariable long id) {
        if (repo.warehouseInUse(id)) throw ApiException.conflict("in_use");
        repo.deleteWarehouse(id);
        return Map.of("ok", true);
    }

    // ---------- Yetkazib beruvchilar ----------
    @GetMapping("/suppliers")
    @RequirePermission("suppliers.view")
    @Operation(summary = "Yetkazib beruvchilar ro'yxati")
    public List<Map<String, Object>> suppliers() { return repo.suppliers(); }

    @PostMapping("/suppliers")
    @RequirePermission("suppliers.create")
    @Operation(summary = "Yetkazib beruvchi yaratish")
    public Map<String, Object> createSupplier(@RequestBody(required = false) JsonNode raw) {
        Body b = Body.of(raw);
        return repo.saveSupplier(null, required(b, "name", 120), b.text("phone", 40), b.text("address", 200), b.text("note", 300), b.flag("active", true));
    }

    @PutMapping("/suppliers/{id}")
    @RequirePermission("suppliers.edit")
    @Operation(summary = "Yetkazib beruvchini tahrirlash")
    public Map<String, Object> updateSupplier(@PathVariable long id, @RequestBody(required = false) JsonNode raw) {
        repo.supplier(id).orElseThrow(ApiException::notFound);
        Body b = Body.of(raw);
        return repo.saveSupplier(id, required(b, "name", 120), b.text("phone", 40), b.text("address", 200), b.text("note", 300), b.flag("active", true));
    }

    @DeleteMapping("/suppliers/{id}")
    @RequirePermission("suppliers.delete")
    @Operation(summary = "Yetkazib beruvchini o'chirish")
    public Map<String, Object> deleteSupplier(@PathVariable long id) {
        if (repo.supplierInUse(id)) throw ApiException.conflict("in_use");
        repo.deleteSupplier(id);
        return Map.of("ok", true);
    }
}
