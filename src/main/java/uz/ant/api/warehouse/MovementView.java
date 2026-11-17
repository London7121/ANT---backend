package uz.ant.api.warehouse;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

/** Ombor hujjati (kirim, chiqim, ko'chirish, hisobdan chiqarish, tuzatish) — frontend kutadigan shakl. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MovementView(long id, String number, String type, String date, long warehouseId, Long toWarehouseId, Long supplierId,
                           String counterparty, String reason, List<LineView> lines, String note, String status,
                           Ref createdBy, String createdAt, Ref cancelledBy, String cancelledAt, String cancelReason) {
    public record LineView(long itemId, BigDecimal qty, BigDecimal price) {}
    public record Ref(long id, String name) {}

    public BigDecimal value() {
        return lines.stream().map(l -> l.qty().multiply(l.price())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
