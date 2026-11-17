package uz.ant.api.content;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import uz.ant.api.common.ApiException;
import uz.ant.api.common.Body;
import uz.ant.api.media.MediaRepository;
import uz.ant.api.security.AuthInterceptor;
import uz.ant.api.security.Permissions;
import uz.ant.api.security.RequirePermission;
import uz.ant.api.security.Staff;

/** Admin: sayt kontenti, sozlamalar, tarjimalar, kollektsiyalar CRUD, xabarlar. */
@RestController
@RequestMapping("/api/admin")
@SecurityRequirement(name = "bearer")
@Tag(name = "Admin: sayt kontenti")
public class AdminContentController {
    private final ContentRepository content;
    private final SettingsService settings;
    private final Schemas schemas;
    private final MessageRepository messages;
    private final MediaRepository media;

    public AdminContentController(ContentRepository content, SettingsService settings, Schemas schemas, MessageRepository messages, MediaRepository media) {
        this.content = content;
        this.settings = settings;
        this.schemas = schemas;
        this.messages = messages;
        this.media = media;
    }

    private static <T> List<T> allow(Staff staff, String module, List<T> value) {
        return Permissions.can(staff, module + ".view") ? value : List.of();
    }

    /** Admin ko'radigan ma'lumot: faqat rolga ruxsat etilgan bo'limlar to'ldiriladi. Parollar hech qachon yuborilmaydi. */
    @GetMapping("/content")
    @Operation(summary = "Admin uchun kontent (ruxsatga qarab to'ldiriladi)")
    public Map<String, Object> adminContent(@RequestAttribute(AuthInterceptor.STAFF) Staff staff) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("settings", settings.settings());
        for (String c : List.of("banners", "products", "posts", "news", "reviews")) m.put(c, allow(staff, c, content.list(c)));
        m.put("media", allow(staff, "media", media.list()));
        m.put("messages", allow(staff, "messages", messages.list()));
        m.put("translations", Permissions.can(staff, "texts.view") ? settings.allTranslations() : emptyTranslations());
        return m;
    }

    private Map<String, Object> emptyTranslations() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String l : Schemas.LANGS) m.put(l, Map.of());
        return m;
    }

    @PutMapping("/settings")
    @RequirePermission("settings.edit")
    @Operation(summary = "Sayt sozlamalarini qisman yangilash", description = "Yuborilgan bo'lim ichidagi yuborilmagan maydonlar saqlanib qoladi; `sections` massivi to'liq almashtiriladi.")
    public ObjectNode putSettings(@RequestBody(required = false) JsonNode body) {
        return settings.patch(schemas.parseSettingsPatch(body));
    }

    @PutMapping("/translations/{lang}")
    @RequirePermission("texts.edit")
    @Operation(summary = "Bir til bo'yicha saytning barcha matnlarini almashtirish", description = "Kalit → matn yoki matnlar ro'yxati. lang: uz | ru | en")
    public ObjectNode putTranslations(@PathVariable String lang, @RequestBody(required = false) JsonNode body) {
        if (!Schemas.LANGS.contains(lang)) throw ApiException.badRequest("invalid_lang");
        ObjectNode t = schemas.parseTranslations(body);
        settings.putTranslations(lang, t);
        return t;
    }

    // ---------- Xabarlar ----------
    @GetMapping("/messages")
    @RequirePermission("messages.view")
    @Operation(summary = "Kelgan xabarlar (yangisi birinchi)")
    public List<Map<String, Object>> messages() { return messages.list(); }

    @PatchMapping("/messages/{id}")
    @RequirePermission("messages.edit")
    @Operation(summary = "Xabarni o'qilgan/o'qilmagan deb belgilash")
    public Map<String, Object> markRead(@PathVariable long id, @RequestBody(required = false) JsonNode body) {
        boolean read = Body.of(body).requireBoolean("read");
        if (!messages.setRead(id, read)) throw ApiException.notFound();
        return messages.find(id).orElseThrow();
    }

    @DeleteMapping("/messages/{id}")
    @RequirePermission("messages.delete")
    @Operation(summary = "Xabarni o'chirish")
    public Map<String, Object> deleteMessage(@PathVariable long id) {
        messages.delete(id);
        return Map.of("ok", true);
    }

    // ---------- Kollektsiyalar: banners / products / posts / news / reviews ----------
    private static void guard(Staff staff, String col, String action) {
        if (!ContentRepository.COLLECTIONS.contains(col)) throw ApiException.notFound();
        String permission = col + "." + action;
        if (!Permissions.can(staff, permission)) throw ApiException.forbiddenPermission(permission);
    }

    @PostMapping("/{col}/reorder")
    @Transactional
    @Operation(summary = "Elementlar tartibini o'zgartirish", description = "col: banners | products | posts | news | reviews")
    public Map<String, Object> reorder(@RequestAttribute(AuthInterceptor.STAFF) Staff staff, @PathVariable String col, @RequestBody(required = false) JsonNode body) {
        guard(staff, col, "edit");
        JsonNode ids = Body.of(body).get("ids");
        if (ids == null || !ids.isArray()) throw ApiException.badRequest("invalid");
        java.util.List<Long> list = new java.util.ArrayList<>();
        for (JsonNode n : ids) {
            if (!n.isIntegralNumber()) throw ApiException.badRequest("invalid");
            list.add(n.asLong());
        }
        content.reorder(col, list);
        return Map.of("ok", true);
    }

    @PostMapping("/{col}")
    @Operation(summary = "Element yaratish", description = "col: banners | products | posts | news | reviews")
    public ObjectNode create(@RequestAttribute(AuthInterceptor.STAFF) Staff staff, @PathVariable String col, @RequestBody(required = false) JsonNode body) {
        guard(staff, col, "create");
        return content.insert(col, schemas.parseItem(col, body));
    }

    @PutMapping("/{col}/{id}")
    @Operation(summary = "Elementni to'liq almashtirish")
    public ObjectNode update(@RequestAttribute(AuthInterceptor.STAFF) Staff staff, @PathVariable String col, @PathVariable long id, @RequestBody(required = false) JsonNode body) {
        guard(staff, col, "edit");
        content.find(col, id).orElseThrow(ApiException::notFound);
        return content.replace(col, id, schemas.parseItem(col, body)).orElseThrow(ApiException::notFound);
    }

    @DeleteMapping("/{col}/{id}")
    @Operation(summary = "Elementni o'chirish")
    public Map<String, Object> delete(@RequestAttribute(AuthInterceptor.STAFF) Staff staff, @PathVariable String col, @PathVariable long id) {
        guard(staff, col, "delete");
        content.delete(col, id);
        return Map.of("ok", true);
    }
}
