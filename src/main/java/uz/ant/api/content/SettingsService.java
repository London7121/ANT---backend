package uz.ant.api.content;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import uz.ant.api.common.Json;

/** Sayt sozlamalari (`kv.settings`) va tarjimalar (`kv.translations.<til>`). */
@Service
public class SettingsService {
    private final KvStore kv;
    private final Json json;
    private final JdbcClient db;

    public SettingsService(KvStore kv, Json json, JdbcClient db) {
        this.kv = kv;
        this.json = json;
        this.db = db;
    }

    public ObjectNode settings() {
        return kv.get("settings").map(n -> (ObjectNode) n).orElseGet(json::object);
    }

    /**
     * Bo'limlar ichida yuborilmagan maydonlar saqlanib qoladi: obyekt qiymatlar birlashtiriladi (`{...old, ...new}`),
     * massiv va oddiy qiymatlar almashtiriladi.
     */
    @Transactional
    public ObjectNode patch(ObjectNode patch) {
        ObjectNode current = kv.getForUpdate("settings").map(n -> (ObjectNode) n).orElseGet(json::object);
        for (var e : patch.properties()) {
            JsonNode value = e.getValue(), old = current.get(e.getKey());
            if (value.isObject() && old != null && old.isObject()) {
                ObjectNode merged = ((ObjectNode) old).deepCopy();
                merged.setAll((ObjectNode) value);
                current.set(e.getKey(), merged);
            } else {
                current.set(e.getKey(), value);
            }
        }
        kv.put("settings", current);
        return current;
    }

    public ObjectNode translations(String lang) {
        return kv.get("translations." + lang).map(n -> (ObjectNode) n).orElseGet(json::object);
    }

    public ObjectNode allTranslations() {
        ObjectNode o = json.object();
        for (String l : Schemas.LANGS) o.set(l, translations(l));
        return o;
    }

    public void putTranslations(String lang, ObjectNode value) { kv.put("translations." + lang, value); }

    public long unblockedUsers() {
        return db.sql("SELECT count(*) FROM site_user WHERE NOT blocked").query(Long.class).single();
    }
}
