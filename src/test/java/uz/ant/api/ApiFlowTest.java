package uz.ant.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Asosiy API oqimlari: kirish, ruxsatlar, ombor (kirim → chiqim → ko'chirish → bekor qilish), kassa. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiFlowTest extends ApiTestBase {
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) { r.add("ant.data-dir", ApiTestBase::freshDataDir); }

    private static String admin;
    private static long itemId, wh2, supplierId, receiptId;

    @Test @Order(1)
    void authAndErrorContract() {
        assertThat(get("/api/admin/content", null).status()).isEqualTo(401);
        assertThat(post("/api/admin/login", null, Map.of("username", "admin", "password", "nope")).error()).isEqualTo("invalid_credentials");
        assertThat(get("/api/no-such-endpoint", null)).satisfies(r -> { assertThat(r.status()).isEqualTo(404); assertThat(r.error()).isEqualTo("not_found"); });
        assertThat(get("/api/content", null).status()).isEqualTo(200);
        admin = login("admin", "test-pass-123");
        assertThat(get("/api/admin/me", admin).body().get("role").asString()).isEqualTo("SUPER_ADMIN");
    }

    @Test @Order(2)
    void permissionsAreEnforcedPerRoleAndPerUserOverride() {
        var created = post("/api/admin/staff", admin, Map.of("username", "worker", "name", "W", "password", "worker-pass-1", "role", "SKLAD_WORKER"));
        assertThat(created.status()).isEqualTo(200);
        String worker = login("worker", "worker-pass-1");
        var denied = get("/api/admin/finance/transactions", worker);
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.body().get("permission").asString()).isEqualTo("finance.view");
        assertThat(post("/api/admin/staff", worker, Map.of("username", "x")).status()).isEqualTo(403);

        long id = created.body().get("id").asLong();
        assertThat(call("PUT", "/api/admin/staff/" + id, admin, Map.of("grant", List.of("finance.view"))).status()).isEqualTo(200);
        assertThat(get("/api/admin/finance/transactions", worker).status()).isEqualTo(200);

        // oxirgi faol SUPER_ADMIN himoyalangan
        long adminId = get("/api/admin/me", admin).body().get("id").asLong();
        assertThat(call("PUT", "/api/admin/staff/" + adminId, admin, Map.of("active", false)).error()).isEqualTo("last_super_admin");
        assertThat(call("DELETE", "/api/admin/staff/" + adminId, admin, null).error()).isEqualTo("cannot_delete_self");
    }

    @Test @Order(3)
    void warehouseDocumentsKeepStockConsistent() {
        wh2 = post("/api/admin/warehouses", admin, Map.of("name", "Filial")).body().get("id").asLong();
        long wh1 = get("/api/admin/warehouses", admin).body().get(0).get("id").asLong();
        supplierId = post("/api/admin/suppliers", admin, Map.of("name", "Yetkazuvchi")).body().get("id").asLong();
        var item = post("/api/admin/catalog", admin, Map.of("sku", "T-1", "name", "Mato", "unit", "m", "barcodes", List.of("4780000000011"), "price", 20));
        itemId = item.body().get("id").asLong();
        assertThat(post("/api/admin/catalog", admin, Map.of("sku", "t-1", "name", "Dup")).error()).isEqualTo("sku_taken");
        assertThat(post("/api/admin/catalog", admin, Map.of("sku", "T-2", "name", "Dup", "barcodes", List.of("4780000000011"))).error()).isEqualTo("barcode_taken");
        assertThat(post("/api/admin/catalog", admin, Map.of("sku", "T-3", "name", "Bad", "barcodes", List.of("a b"))).error()).isEqualTo("invalid_barcode");

        var receipt = post("/api/admin/movements", admin, Map.of("type", "RECEIPT", "warehouseId", wh1, "supplierId", supplierId, "lines", List.of(Map.of("itemId", itemId, "qty", 10, "price", 12.5))));
        assertThat(receipt.status()).isEqualTo(200);
        assertThat(receipt.body().get("number").asString()).isEqualTo("KR-00001");
        receiptId = receipt.body().get("id").asLong();
        assertThat(stock(itemId, wh1)).isEqualTo(10.0);

        var tooMuch = post("/api/admin/movements", admin, Map.of("type", "ISSUE", "warehouseId", wh1, "lines", List.of(Map.of("itemId", itemId, "qty", 11))));
        assertThat(tooMuch.status()).isEqualTo(409);
        assertThat(tooMuch.error()).isEqualTo("insufficient_stock");
        assertThat(post("/api/admin/movements", admin, Map.of("type", "ISSUE", "warehouseId", wh1, "lines", List.of(Map.of("itemId", itemId, "qty", 0)))).error()).isEqualTo("invalid_qty");

        assertThat(post("/api/admin/movements", admin, Map.of("type", "TRANSFER", "warehouseId", wh1, "toWarehouseId", wh2, "lines", List.of(Map.of("itemId", itemId, "qty", 4)))).status()).isEqualTo(200);
        assertThat(stock(itemId, wh1)).isEqualTo(6.0);
        assertThat(stock(itemId, wh2)).isEqualTo(4.0);

        // kirimni bekor qilsak, qoldiq manfiy bo'lib qoladi (6 + 4 ko'chirilgan − 10 < 0 filialda emas, asosiy omborda 6−10)
        var cancel = post("/api/admin/movements/" + receiptId + "/cancel", admin, Map.of("reason", "xato"));
        assertThat(cancel.status()).isEqualTo(409);
        assertThat(cancel.error()).isEqualTo("cancel_would_go_negative");
        assertThat(post("/api/admin/movements/" + receiptId + "/cancel", admin, Map.of()).error()).isEqualTo("reason_required");

        // ishlatilgan tovar/ombor/yetkazib beruvchi o'chmaydi
        assertThat(call("DELETE", "/api/admin/catalog/" + itemId, admin, null).error()).isEqualTo("in_use");
        assertThat(call("DELETE", "/api/admin/warehouses/" + wh2, admin, null).error()).isEqualTo("in_use");
        assertThat(call("DELETE", "/api/admin/suppliers/" + supplierId, admin, null).error()).isEqualTo("in_use");
    }

    @Test @Order(4)
    void parallelIssuesCannotOverdrawStock() throws Exception {
        long wh1 = get("/api/admin/warehouses", admin).body().get(0).get("id").asLong();
        // asosiy omborda 6 ta bor; 8 ta parallel "5 ta chiqim" — faqat bittasi o'tishi kerak
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Res>> results = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Res> task = () -> {
                go.await();
                return post("/api/admin/movements", admin, Map.of("type", "ISSUE", "warehouseId", wh1, "lines", List.of(Map.of("itemId", itemId, "qty", 5))));
            };
            results.add(pool.submit(task));
        }
        go.countDown();
        long ok = 0, rejected = 0;
        for (Future<Res> f : results) {
            Res r = f.get();
            if (r.status() == 200) ok++;
            else if (r.status() == 409) rejected++;
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
        assertThat(rejected).isEqualTo(threads - 1);
        assertThat(stock(itemId, wh1)).isEqualTo(1.0);
    }

    @Test @Order(5)
    void cashBookPayablesAndReport() {
        // yetkazib beruvchidan 10 × 12.5 = 125 olingan
        var tx = post("/api/admin/finance/transactions", admin, Map.of("type", "expense", "category", "supplier_payment", "amount", 100, "supplierId", supplierId));
        assertThat(tx.status()).isEqualTo(200);
        assertThat(post("/api/admin/finance/transactions", admin, Map.of("type", "expense", "category", "supplier_payment", "amount", 5)).error()).isEqualTo("supplier_required");
        assertThat(post("/api/admin/finance/transactions", admin, Map.of("type", "income", "category", "rent", "amount", 5)).error()).isEqualTo("invalid");
        post("/api/admin/finance/transactions", admin, Map.of("type", "income", "category", "sales", "amount", 300.5));

        var payables = get("/api/admin/finance/payables", admin).body().get(0);
        assertThat(payables.get("purchased").asDouble()).isEqualTo(125.0);
        assertThat(payables.get("paid").asDouble()).isEqualTo(100.0);
        assertThat(payables.get("balance").asDouble()).isEqualTo(25.0);

        var report = get("/api/admin/reports", admin).body();
        assertThat(report.at("/finance/income").asDouble()).isEqualTo(300.5);
        assertThat(report.at("/finance/expense").asDouble()).isEqualTo(100.0);
        assertThat(report.at("/finance/net").asDouble()).isEqualTo(200.5);
        assertThat(report.at("/payables/total").asDouble()).isEqualTo(25.0);
        assertThat(get("/api/admin/stats", admin).body().at("/finance/payablesTotal").asDouble()).isEqualTo(25.0);
    }

    @Test @Order(6)
    void siteUserRegistersReviewsAndGetsBlocked() {
        var reg = post("/api/auth/register", null, Map.of("username", "Mijoz1", "password", "1234"));
        assertThat(reg.status()).isEqualTo(200);
        assertThat(post("/api/auth/register", null, Map.of("username", "mijoz1", "password", "1234")).error()).isEqualTo("username_taken");
        String token = reg.body().get("token").asString();
        assertThat(call("PUT", "/api/me/review", token, Map.of("rating", 5, "text", "Zo'r")).status()).isEqualTo(200);
        assertThat(call("PUT", "/api/me/review", token, Map.of("rating", 4, "text", "Yaxshi")).status()).isEqualTo(200); // yangilanadi, ko'paymaydi
        var content = get("/api/content", null).body();
        long mine = content.get("reviews").valueStream().filter(r -> "Mijoz1".equals(r.path("name").asString())).count();
        assertThat(mine).isEqualTo(1);
        assertThat(content.at("/settings/stats/clients").asLong()).isEqualTo(1);

        long userId = reg.body().at("/user/id").asLong();
        assertThat(call("PATCH", "/api/admin/users/" + userId, admin, Map.of("blocked", true)).status()).isEqualTo(200);
        assertThat(get("/api/me", token).status()).isEqualTo(403);
        assertThat(get("/api/content", null).body().at("/settings/stats/clients").asLong()).isEqualTo(0);
    }

    private double stock(long item, long warehouse) {
        for (var it : get("/api/admin/catalog", admin).body()) {
            if (it.get("id").asLong() == item) return it.at("/stock/" + warehouse).asDouble(0);
        }
        return Double.NaN;
    }
}
