package uz.ant.api.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Admin panel ruxsatlari. Ruxsat = `modul.amal` (masalan `catalog.edit`).
 * Har rolning standart ruxsatlari bor; har bir xodimga alohida qo'shimcha (grant) yoki olib tashlangan (revoke) ruxsat berilishi mumkin.
 *
 * DIQQAT: frontenddagi `ant-frontend/shared/roles.ts` shu jadvalning nusxasi (menyu/tugmalar uchun).
 * Ikkalasini birga o'zgartiring — PermissionsTest shu jadvalni tekshiradi.
 */
public final class Permissions {
    private Permissions() {}

    public static final List<String> MODULES = List.of(
            "dashboard", "settings", "banners", "products", "posts", "news", "reviews", "users", "texts", "media", "messages", "staff",
            "warehouses", "catalog", "stock", "movements", "inventory", "suppliers", "finance", "reports");

    private static final List<String> CRUD = List.of("view", "create", "edit", "delete");

    private static List<String> plus(List<String> base, String extra) {
        List<String> l = new ArrayList<>(base);
        l.add(extra);
        return l;
    }

    /** Har bir modulda qaysi amallar mavjud. `view` — boshqa amallar uchun old shart. */
    public static final Map<String, List<String>> MODULE_ACTIONS = new LinkedHashMap<>();
    static {
        MODULE_ACTIONS.put("dashboard", List.of("view"));
        MODULE_ACTIONS.put("settings", List.of("view", "edit"));
        MODULE_ACTIONS.put("banners", CRUD);
        MODULE_ACTIONS.put("products", CRUD);
        MODULE_ACTIONS.put("posts", CRUD);
        MODULE_ACTIONS.put("news", CRUD);
        MODULE_ACTIONS.put("reviews", CRUD);
        MODULE_ACTIONS.put("users", List.of("view", "edit", "delete"));
        MODULE_ACTIONS.put("texts", List.of("view", "edit"));
        MODULE_ACTIONS.put("media", List.of("view", "create", "delete"));
        MODULE_ACTIONS.put("messages", List.of("view", "edit", "delete"));
        MODULE_ACTIONS.put("staff", CRUD);
        MODULE_ACTIONS.put("warehouses", CRUD);
        MODULE_ACTIONS.put("catalog", plus(CRUD, "export"));
        MODULE_ACTIONS.put("stock", List.of("view", "export"));
        MODULE_ACTIONS.put("movements", List.of("view", "create", "delete", "export")); // delete = harakatni bekor qilish
        MODULE_ACTIONS.put("inventory", List.of("view", "create"));
        MODULE_ACTIONS.put("suppliers", CRUD);
        MODULE_ACTIONS.put("finance", plus(CRUD, "export"));
        MODULE_ACTIONS.put("reports", List.of("view", "export"));
    }

    public static final List<String> ALL = MODULES.stream()
            .flatMap(m -> MODULE_ACTIONS.get(m).stream().map(a -> m + "." + a)).toList();
    private static final Set<String> ALL_SET = Set.copyOf(ALL);

    public static boolean isPermission(String value) { return value != null && ALL_SET.contains(value); }

    private static List<String> full(String... modules) {
        List<String> out = new ArrayList<>();
        for (String m : modules) for (String a : MODULE_ACTIONS.get(m)) out.add(m + "." + a);
        return out;
    }

    private static List<String> only(String action, String... modules) {
        List<String> out = new ArrayList<>();
        for (String m : modules) out.add(m + "." + action);
        return out;
    }

    @SafeVarargs
    private static List<String> concat(List<String>... parts) {
        List<String> out = new ArrayList<>();
        for (List<String> p : parts) out.addAll(p);
        return out;
    }

    /** Rollarning standart ruxsatlari. */
    public static final Map<Role, List<String>> ROLE_PERMISSIONS = new EnumMap<>(Role.class);
    static {
        ROLE_PERMISSIONS.put(Role.SUPER_ADMIN, ALL);
        // Kontent va ombor to'liq; buxgalteriyani faqat ko'radi; xodimlarni boshqara olmaydi
        ROLE_PERMISSIONS.put(Role.ADMIN, concat(
                full("dashboard", "settings", "banners", "products", "posts", "news", "reviews", "users", "texts", "media", "messages"),
                full("warehouses", "catalog", "stock", "movements", "inventory", "suppliers"),
                only("view", "finance", "reports"), List.of("reports.export")));
        // Buxgalter: moliya va hisobotlar to'liq; ombor ma'lumotlarini ko'radi va eksport qiladi
        ROLE_PERMISSIONS.put(Role.BUXGALTER, concat(
                only("view", "dashboard", "products", "reviews", "messages", "users", "warehouses", "catalog", "stock", "movements", "inventory", "suppliers"),
                only("export", "catalog", "stock", "movements"),
                full("finance", "reports")));
        // Ombor xodimi: tovar, kirim/chiqim, inventarizatsiya; hech narsani o'chira olmaydi
        ROLE_PERMISSIONS.put(Role.SKLAD_WORKER, concat(
                only("view", "dashboard", "products", "media", "warehouses", "catalog", "stock", "movements", "inventory", "suppliers"),
                List.of("catalog.create", "catalog.edit", "catalog.export", "stock.export",
                        "movements.create", "movements.export", "inventory.create",
                        "suppliers.create", "suppliers.edit", "media.create")));
    }

    /** Rol + shaxsiy o'zgarishlar bo'yicha yakuniy ruxsatlar. SUPER_ADMIN har doim hammasiga ega. */
    public static Set<String> effective(Role role, Collection<String> grant, Collection<String> revoke) {
        if (role == Role.SUPER_ADMIN) return new LinkedHashSet<>(ALL);
        Set<String> set = new LinkedHashSet<>(ROLE_PERMISSIONS.get(role));
        set.addAll(grant);
        set.removeAll(revoke);
        for (String m : MODULES) {
            String view = m + ".view";
            boolean hasOther = MODULE_ACTIONS.get(m).stream().anyMatch(a -> set.contains(m + "." + a));
            if (hasOther && !revoke.contains(view)) set.add(view);
            if (!set.contains(view)) for (String a : MODULE_ACTIONS.get(m)) set.remove(m + "." + a);
        }
        return set;
    }

    public static boolean can(Staff staff, String permission) {
        return staff != null && effective(staff.role(), staff.grant(), staff.revoke()).contains(permission);
    }
}
