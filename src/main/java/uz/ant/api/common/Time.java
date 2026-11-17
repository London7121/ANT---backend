package uz.ant.api.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;
import uz.ant.api.config.AntProperties;

/** Vaqt yordamchilari: kunlar sozlangan vaqt zonasi (standart Asia/Tashkent) bo'yicha, vaqt belgilari ISO-8601 (ms, Z). */
@Component
public class Time {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC);
    private final ZoneId zone;

    public Time(AntProperties props) {
        this.zone = props.timezone();
    }

    public ZoneId zone() { return zone; }
    public LocalDate today() { return LocalDate.now(zone); }

    /** YYYY-MM-DD matnini sana qiladi; noto'g'ri yoki bo'sh bo'lsa null. */
    public static LocalDate parseDate(String s) {
        try {
            return s == null || s.isBlank() ? null : LocalDate.parse(s.trim());
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    /** JS `toISOString()` bilan bir xil ko'rinish: 2026-11-11T10:08:30.499Z */
    public static String iso(Instant t) { return t == null ? null : ISO.format(t); }
}
