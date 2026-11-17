package uz.ant.api.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import uz.ant.api.common.Json;
import uz.ant.api.config.AntProperties;
import uz.ant.api.content.ContentRepository;
import uz.ant.api.content.KvStore;
import uz.ant.api.content.Schemas;
import uz.ant.api.security.PasswordHasher;
import uz.ant.api.security.Role;
import uz.ant.api.staff.StaffRepository;

/**
 * Birinchi ishga tushirish: bo'sh bazaga standart kontent + Super admin yoziladi, yoki `IMPORT_JSON` berilsa eski Node versiyasining
 * `db.json` fayli import qilinadi. Veb-server ochilishidan OLDIN ishlaydi (token kaliti tayyor bo'lishi uchun).
 */
@Component
public class DataBootstrap implements SmartInitializingSingleton {
    private static final Logger log = LoggerFactory.getLogger(DataBootstrap.class);

    private final AntProperties props;
    private final StaffRepository staff;
    private final KvStore kv;
    private final ContentRepository content;
    private final JdbcClient db;
    private final Json json;
    private final PasswordHasher hasher;
    private final LegacyJsonImporter importer;
    private final TransactionTemplate tx;

    public DataBootstrap(AntProperties props, StaffRepository staff, KvStore kv, ContentRepository content, JdbcClient db, Json json,
                         PasswordHasher hasher, LegacyJsonImporter importer, TransactionTemplate tx) {
        this.props = props;
        this.staff = staff;
        this.kv = kv;
        this.content = content;
        this.db = db;
        this.json = json;
        this.hasher = hasher;
        this.importer = importer;
        this.tx = tx;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!props.storage().isS3()) try {
            Files.createDirectories(props.uploadDir());
        } catch (IOException e) {
            throw new IllegalStateException("Yuklash papkasini yaratib bo'lmadi: " + props.uploadDir(), e);
        }
        boolean fresh = staff.count() == 0 && kv.get("secret").isEmpty();
        if (!fresh) return;
        if (props.importJson() != null && !props.importJson().isBlank()) {
            Path file = Path.of(props.importJson()).toAbsolutePath();
            log.info("Eski db.json import qilinmoqda: {}", file);
            tx.executeWithoutResult(s -> importer.run(file));
            log.info("Import yakunlandi.");
        } else {
            tx.executeWithoutResult(s -> seed());
        }
    }

    /** Standart kontent (landing demo ma'lumoti) — `seed/default-content.json` (shared/content.ts dan olingan). */
    public ObjectNode defaultContent() {
        try (InputStream in = new ClassPathResource("seed/default-content.json").getInputStream()) {
            return (ObjectNode) json.mapper().readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void seed() {
        ObjectNode seed = defaultContent();
        kv.put("settings", seed.get("settings"));
        for (String lang : Schemas.LANGS) kv.put("translations." + lang, json.object());
        for (String col : ContentRepository.COLLECTIONS) {
            for (JsonNode item : seed.get(col)) content.insertWithId(col, item.get("id").asLong(), (ObjectNode) item);
        }
        db.sql("INSERT INTO warehouse(name) VALUES ('Asosiy ombor')").update();

        String password = props.adminPassword() == null || props.adminPassword().isBlank() ? "admin123" : props.adminPassword();
        if (props.adminPassword() == null || props.adminPassword().isBlank()) {
            log.warn("\n⚠️  ADMIN_PASSWORD berilmagan: standart login \"{}\" / \"admin123\". Admin paneldan darhol almashtiring!\n", props.adminUser());
        }
        staff.insert(props.adminUser(), "Super admin", Role.SUPER_ADMIN, List.of(), List.of(), true, hasher.hash(password), Instant.now());
        kv.put("secret", json.mapper().valueToTree(newSecret()));
        log.info("Bo'sh baza standart kontent bilan to'ldirildi.");
    }

    static String newSecret() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
