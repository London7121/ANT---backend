package uz.ant.api.dev;

import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Lokal ishlab chiqish va testlar uchun PostgreSQL'ni o'zi ishga tushiradi (o'rnatish yoki Docker shart emas).
 * Ma'lumotlar `${ant.data-dir}/pg` papkasida saqlanadi — qayta ishga tushirganda yo'qolmaydi.
 * Productionda ishlatilmaydi: u yerda DB_URL / DB_USER / DB_PASSWORD bilan tashqi PostgreSQL ulanadi.
 */
@TestConfiguration(proxyBeanMethods = false)
public class EmbeddedPostgresConfig {

    @Bean(destroyMethod = "close")
    EmbeddedPostgres embeddedPostgres(@Value("${ant.data-dir}") String dataDir) throws IOException {
        Path pg = Path.of(dataDir).toAbsolutePath().normalize().resolve("pg");
        Files.createDirectories(pg);
        return EmbeddedPostgres.builder()
                .setDataDirectory(pg)
                .setCleanDataDirectory(false)
                .setPort(0)
                .start();
    }

    @Bean(destroyMethod = "close")
    DataSource dataSource(EmbeddedPostgres pg) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(pg.getJdbcUrl("postgres", "postgres"));
        ds.setUsername("postgres");
        ds.setPassword("postgres");
        ds.setMaximumPoolSize(10);
        return ds;
    }
}
