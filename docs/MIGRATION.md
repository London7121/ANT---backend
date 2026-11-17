# Node → Java ko'chirish: nima qilindi va nega

Eski backend (`legacy-node/`: Express + JSON fayl) **bir xil API kontrakti** bilan Java'ga (Spring Boot 4 + PostgreSQL) o'tkazildi.
Frontend **o'zgarmadi** (faqat `playwright.config.ts` endi Java API'ni ko'taradi). Qabul mezoni: eski Playwright e2e testlari
Java API'ga qarshi to'liq o'tdi (38 ta o'tdi, 20 ta skip — desktop/mobile uchun mo'ljallangan).

## Sprintlar

| Sprint | Natija |
|---|---|
| **0. Tahlil** | Barcha ~2000 qator Node kodi o'qildi; endpointlar, xato kodlari, ruxsatlar, parol/token formati ro'yxatga olindi; texnologiya tanlandi. |
| **1. Skelet** | Maven loyiha, Spring Boot 4, `application.yml`, Flyway `V1__init.sql` (sxema), yagona xato formati, dev uchun o'rnatilgan PostgreSQL. |
| **2. Xavfsizlik** | Rollar/ruxsatlar matritsasi, scrypt parol xeshi va HMAC token (Node formati bilan **bayt-bayt mos**), rate limit, kirish endpointlari. |
| **3. Sayt kontenti** | `GET /api/content`, aloqa formasi, banner/mahsulot/post/yangilik/sharh CRUD + tartiblash, sozlamalar (qisman yangilash), 3 tilli matnlar, xabarlar. |
| **4. Foydalanuvchilar, xodimlar, media, dashboard** | Mijoz ro'yxatdan o'tishi/sevimlilar/sharh, xodimlar boshqaruvi (oxirgi Super admin himoyasi), rasm qayta ishlash (WebP), statistika. |
| **5. Ombor** | Katalog, shtrix kodlar, omborlar, yetkazib beruvchilar, hujjatlar (kirim/chiqim/ko'chirish/hisobdan chiqarish), bekor qilish, inventarizatsiya. Qoldiq — SQL ko'rinishi. |
| **6. Buxgalteriya** | Kassa, qarzdorlik, davr hisoboti. |
| **7. Ko'chirish va chiqarish** | `db.json` importeri, seed, `--reset-admin`, Swagger, testlar, jar/Docker, IntelliJ run konfiguratsiyalari. |

## Arxitektura (paketlar)

```
uz.ant.api
├─ AntApplication            kirish nuqtasi
├─ config/                   AntProperties (ant.* sozlamalar), WebConfig (interceptor, /uploads, SPA), RequestFilters, OpenApiConfig
├─ common/                   ApiException + GlobalExceptionHandler (xato formati), Body (so'rov tekshiruvi), Counters, Json, Time
├─ security/                 Permissions (ruxsat matritsasi), PasswordHasher, TokenService, AuthInterceptor, RateLimitInterceptor, @RequirePermission, @RateLimit
├─ account/                  mijoz kirishi, /api/me, admin kirishi/profili, admin→foydalanuvchilar
├─ staff/                    xodimlar
├─ content/                  JSONB kontent (ContentRepository, Schemas, SettingsService), public API, admin kontent, xabarlar
├─ media/                    yuklash, Thumbnailator + WebP
├─ warehouse/                katalog, qoldiq, hujjatlar (MovementService), omborlar/yetkazib beruvchilar
├─ finance/                  kassa, qarzdorlik, hisobotlar
├─ stats/                    dashboard
└─ bootstrap/                DataBootstrap (seed/import), LegacyJsonImporter, AdminResetRunner
```

**So'rov yo'li:** `RequestFilters` (hajm/xavfsizlik sarlavhalari) → `RateLimitInterceptor` (`@RateLimit`) → `AuthInterceptor`
(token → xodim/mijoz; `@RequirePermission` tekshiruvi) → controller → servis/repository (`JdbcClient`, SQL) → PostgreSQL.
Xatolar `ApiException` bilan tashlanadi va `GlobalExceptionHandler` ularni `{ "error": "kod" }` ga aylantiradi.

## Muhim qarorlar

1. **Spring Boot + JdbcClient + Flyway (JPA yo'q).** Ma'lumot modeli oddiy, SQL ko'rinib turadi, qoldiq hisobi uchun SQL ko'rinishlari qulay. Sxema o'zgarishi — yangi `V2__….sql` fayl.
2. **Sayt kontenti — JSONB.** Admin maydonlari ko'p tilli va erkin (`looseObject`: noma'lum maydonlar saqlanadi), shuning uchun banner/mahsulot/post/yangilik/sharh `content_item` jadvalida hujjat sifatida; sozlamalar va tarjimalar — `kv`. Xodim, mijoz, ombor, kassa — oddiy relyatsion jadvallar (FK, UNIQUE, CHECK).
3. **Spring Security ishlatilmadi.** Frontend bilan allaqachon kelishilgan token formati bor; uni bayt-bayt saqlash uchun kichik o'z interceptor'i yetarli va soddaroq. Shuning uchun **eski parollar va sessiyalar ko'chirishdan keyin ham ishlaydi**.
4. **Pul va miqdor — `BigDecimal` / `numeric`** (JS `float` o'rniga). Pul 2 xona, miqdor 3 xona; tannarx/narx 4 xonagacha (kirim narxi 2 xonadan ortiq bo'lishi mumkin edi).
5. **Qoldiq saqlanmaydi** — `stock` ko'rinishi tasdiqlangan hujjatlardan hisoblaydi (eski mantiq bilan bir xil). Hujjat o'tkazish/bekor qilish `pg_advisory_xact_lock` bilan ketma-ketlashtirilgan: parallel so'rovlar qoldiqni manfiy qila olmaydi (test bor). Hujjat raqami — atomik hisoblagich (`counters`).
6. **ID'lar qayta ishlatilmaydi.** Node `max(id)+1` ishlatgan edi (o'chirilgan oxirgi ID qayta berilardi, sevimlilar boshqa mahsulotga "o'tib" qolishi mumkin edi). Endi identity/hisoblagich.
7. **Dev uchun o'rnatilgan PostgreSQL** (zonky, faqat test classpath'da): production jar'ga kirmaydi. IntelliJ'da `DevApplication`, konsolda `spring-boot:test-run`.

## Eski xatti-harakatdan ataylab farqlar

| Nima | Eski | Yangi | Sabab |
|---|---|---|---|
| Kun chegarasi | UTC | `TIMEZONE` (standart `Asia/Tashkent`) | Toshkent vaqti bilan 00:00–05:00 oralig'ida "bugun" noto'g'ri kun chiqardi |
| Buzuq JSON tana | 500 | 400 `invalid` | Mijoz xatosi 500 emas |
| Juda katta fayl | 500 | 413 `file_too_large` | |
| Buzuq/qo'llab-quvvatlanmaydigan rasm | 500 | 400 `invalid_image` | |
| AVIF yuklash | WebP'ga o'tkaziladi | O'zgartirilmasdan saqlanadi | Java'da AVIF'ni o'qiydigan kutubxona yo'q |
| `index.html` keshi | 1 soat | har safar tekshiriladi (`no-cache`); `/assets/*` — 1 yil | Yangi deploydan keyin eski `index.html` qolib ketmasin |
| ID | `max+1` | hisoblagich | 6-bandga qarang |
| Swagger | qo'lda yozilgan `openapi.ts` | controller'lardan avtomatik (springdoc) | Hujjat kod bilan hech qachon ajralib qolmaydi |

## Cheklovlar va ochiq masalalar

- **Ruxsatlar jadvali ikki nusxada** (`Permissions.java` va `shared/roles.ts`) — menyu/tugmalar frontendda kerak. `PermissionsTest` ularni solishtiradi; o'zgartirganda `npm run export:permissions`. Keyingi qadam: frontend ruxsatlarni `/api/admin/me` dan olsin.
- **Swagger'da so'rov tanalari sxemasiz** — controller'lar `JsonNode` qabul qiladi (eski zod'ning "yumshoq" tozalash qoidalarini aynan saqlash uchun); tavsiflar `@Operation` da. Xohlansa, alohida `@Schema` yozuvlari qo'shiladi.
- **Docker fayllari sinab ko'rilmagan** (bu muhitda Docker yo'q).
- **Tashqi (o'rnatilgan emas) PostgreSQL bilan** to'liq e2e yurgizilmagan: jar o'rnatilgan PostgreSQL'ning TCP porti orqali tekshirildi (ulanish, migratsiya, WebP, SPA ishladi). Boshqa PostgreSQL versiyalari (16+) kutiladi, lekin sinalmagan.
- `legacy-node/` hozircha saqlab turilibdi (andoza va `db.json` manbasi). Barcha ma'lumot import qilinib tasdiqlangach, o'chirish mumkin — loyiha git'da emas, shuning uchun avval zaxira oling.
