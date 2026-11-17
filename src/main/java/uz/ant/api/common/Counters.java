package uz.ant.api.common;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Atomik hisoblagichlar: hujjat raqamlari va kontent ID'lari (o'chirilgan ID qayta ishlatilmaydi). */
@Repository
public class Counters {
    private final JdbcClient db;

    public Counters(JdbcClient db) { this.db = db; }

    /** Keyingi qiymat (1, 2, 3 …). Tranzaksiya bekor bo'lsa, raqam ham qaytadi. */
    public long next(String name) {
        return db.sql("INSERT INTO counters(name, value) VALUES (:n, 1) ON CONFLICT (name) DO UPDATE SET value = counters.value + 1 RETURNING value")
                .param("n", name).query(Long.class).single();
    }

    /** Hisoblagich kamida `value` bo'lsin (import / seed'dan keyin). */
    public void raiseTo(String name, long value) {
        db.sql("INSERT INTO counters(name, value) VALUES (:n, :v) ON CONFLICT (name) DO UPDATE SET value = GREATEST(counters.value, excluded.value)")
                .param("n", name).param("v", value).update();
    }
}
