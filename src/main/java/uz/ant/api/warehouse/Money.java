package uz.ant.api.warehouse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import tools.jackson.databind.JsonNode;

/** Pul va miqdor yordamchilari: pul — 2 xona, miqdor — 3 xona (JS `Math.round` kabi yarimdan yuqoriga). */
public final class Money {
    private Money() {}

    public static BigDecimal money(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP); }
    public static BigDecimal qty(BigDecimal v) { return v.setScale(3, RoundingMode.HALF_UP); }

    /** JSON son bo'lsa qiymat, aks holda null (JS: `typeof v === 'number' && isFinite`). */
    public static BigDecimal numberOrNull(JsonNode v) {
        return v != null && v.isNumber() ? v.decimalValue() : null;
    }
}
