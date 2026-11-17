import { z } from 'zod'
import { MOVEMENT_TYPES } from '@ant/shared/warehouse'
import '@/schemas' // sxemalarni ro'yxatdan o'tkazadi (.meta({ id }))

/** Sxemalar `@/schemas` dan olinadi: validatsiya va hujjat bir manbadan. io:'input' — mijoz yuboradigan shakl. */
const generated = z.toJSONSchema(z.globalRegistry, { io: 'input', uri: (id) => `#/components/schemas/${id}` }).schemas as Record<string, Record<string, unknown>>
const schemas = Object.fromEntries(
  Object.entries(generated).map(([name, { $schema: _s, $id: _i, ...rest }]) => {
    // `.catch()/.default()` li maydonlar so'rovda ixtiyoriy (server o'zi to'ldiradi)
    const props = (rest.properties ?? {}) as Record<string, Record<string, unknown>>
    const required = ((rest.required ?? []) as string[]).filter((k) => !('default' in (props[k] ?? {})))
    return [name, { ...rest, ...(required.length ? { required } : { required: undefined }) }]
  }),
)

/** OpenAPI 3.1 — qo'lda yozilgan, qisqa yordamchilar bilan. Swagger UI: /api/docs */

type Json = Record<string, unknown>
const ref = (name: string) => ({ $ref: `#/components/schemas/${name}` })
const body = (schema: Json, description?: string) => ({ required: true, description, content: { 'application/json': { schema } } })
const ok = (schema: Json = ref('Ok'), description = 'Muvaffaqiyatli') => ({ description, content: { 'application/json': { schema } } })
const err = (description: string) => ({ description, content: { 'application/json': { schema: ref('Error') } } })
const id = { name: 'id', in: 'path', required: true, schema: { type: 'integer' } }
const q = (name: string, description: string, schema: Json = { type: 'string' }) => ({ name, in: 'query', description, schema })
const arr = (schema: Json) => ({ type: 'array', items: schema })
const obj = (properties: Json, required?: string[]): Json => ({ type: 'object', properties, ...(required ? { required } : {}) })

const COLLECTION_ITEM = { oneOf: ['Banner', 'Product', 'Post', 'NewsItem', 'ReviewAdmin'].map(ref), description: 'Tanlangan to‘plamga mos shakl: banners → Banner, products → Product, posts → Post, news → NewsItem, reviews → ReviewAdmin' }
const staffAuth = [{ staffToken: [] }]
const userAuth = [{ userToken: [] }]
const std = { 401: err('Token yo‘q yoki yaroqsiz'), 403: err('Ruxsat yo‘q (modul.amal)') }

/** Bitta modul uchun standart ro'yxat/yaratish/tahrirlash/o'chirish yo'llari. */
function crud(base: string, tag: string, module: string, schema: string, input: Json) {
  return {
    [base]: {
      get: { tags: [tag], summary: `${tag}: ro'yxat`, description: `Ruxsat: \`${module}.view\``, security: staffAuth, responses: { 200: ok(arr(ref(schema))), ...std } },
      post: { tags: [tag], summary: `${tag}: yaratish`, description: `Ruxsat: \`${module}.create\``, security: staffAuth, requestBody: body(input), responses: { 200: ok(ref(schema)), 400: err('Noto‘g‘ri ma’lumot'), ...std } },
    },
    [`${base}/{id}`]: {
      put: { tags: [tag], summary: `${tag}: tahrirlash`, description: `Ruxsat: \`${module}.edit\``, security: staffAuth, parameters: [id], requestBody: body(input), responses: { 200: ok(ref(schema)), 400: err('Noto‘g‘ri ma’lumot'), 404: err('Topilmadi'), ...std } },
      delete: { tags: [tag], summary: `${tag}: o'chirish`, description: `Ruxsat: \`${module}.delete\`. Ishlatilayotgan yozuv o‘chirilmaydi (409).`, security: staffAuth, parameters: [id], responses: { 200: ok(), 404: err('Topilmadi'), 409: err('Ishlatilmoqda'), ...std } },
    },
  }
}


export const openapi = {
  openapi: '3.1.0',
  info: {
    title: 'ANT API',
    version: '1.0.0',
    description: [
      'ANT landing, admin panel, ombor va buxgalteriya API.',
      '',
      '**Autentifikatsiya.** `POST /admin/login` (xodim) yoki `POST /auth/login` (sayt foydalanuvchisi) javobidagi `token` ni yuqoridagi **Authorize** tugmasiga kiriting.',
      '',
      '**Ruxsatlar.** Har bir admin amali `modul.amal` ruxsatini talab qiladi (view/create/edit/delete/export). Ruxsat yo‘q bo‘lsa — `403`.',
      '',
      '**Xatolar.** Hammasi `{ "error": "kod" }` ko‘rinishida.',
    ].join('\n'),
  },
  servers: [{ url: '/api' }],
  tags: [
    { name: 'Sayt', description: 'Ochiq: landing kontenti va aloqa formasi' },
    { name: 'Foydalanuvchi', description: 'Sayt foydalanuvchisi: ro‘yxatdan o‘tish, sevimlilar, sharh' },
    { name: 'Admin: kirish', description: 'Xodim kirishi va profili' },
    { name: 'Admin: kontent' }, { name: 'Admin: to‘plamlar' }, { name: 'Admin: media' }, { name: 'Admin: xabarlar' },
    { name: 'Admin: foydalanuvchilar' }, { name: 'Admin: xodimlar' }, { name: 'Admin: statistika' },
    { name: 'Ombor: omborlar' }, { name: 'Ombor: yetkazib beruvchilar' }, { name: 'Ombor: katalog' },
    { name: 'Ombor: harakatlar' }, { name: 'Buxgalteriya' },
  ],
  components: {
    securitySchemes: {
      staffToken: { type: 'http', scheme: 'bearer', description: '`POST /admin/login` tokeni (12 soat)' },
      userToken: { type: 'http', scheme: 'bearer', description: '`POST /auth/login` tokeni (1 yil)' },
    },
    schemas,
  },
  paths: {
    // ---- Sayt ----
    '/content': { get: { tags: ['Sayt'], summary: 'Landing kontenti (yashirin elementlarsiz)', responses: { 200: ok(obj({ settings: ref('SettingsInput'), banners: arr(ref('Banner')), products: arr(ref('Product')), posts: arr(ref('Post')), news: arr(ref('NewsItem')), reviews: arr(ref('Review')), translations: { type: 'object', additionalProperties: ref('TranslationsInput') } })) } } },
    '/contact': { post: { tags: ['Sayt'], summary: 'Aloqa formasi', description: 'Soatiga 5 ta so‘rov (IP bo‘yicha).', requestBody: body(ref('ContactInput')), responses: { 200: ok(), 400: err('Noto‘g‘ri ma’lumot'), 429: err('Juda ko‘p so‘rov') } } },

    // ---- Foydalanuvchi ----
    '/auth/register': { post: { tags: ['Foydalanuvchi'], summary: 'Ro‘yxatdan o‘tish', requestBody: body(ref('SiteRegister')), responses: { 200: ok(ref('SiteSession')), 400: err('Noto‘g‘ri'), 409: err('username_taken') } } },
    '/auth/login': { post: { tags: ['Foydalanuvchi'], summary: 'Kirish', requestBody: body(ref('SiteLogin')), responses: { 200: ok(ref('SiteSession')), 401: err('invalid_credentials'), 403: err('blocked') } } },
    '/me': { get: { tags: ['Foydalanuvchi'], summary: 'Mening ma’lumotlarim', security: userAuth, responses: { 200: ok(), 401: err('Token yo‘q') } } },
    '/me/favorites': { put: { tags: ['Foydalanuvchi'], summary: 'Sevimlilarni saqlash', security: userAuth, requestBody: body(ref('FavoritesInput')), responses: { 200: ok(obj({ favorites: arr({ type: 'integer' }) })), 401: err('Token yo‘q') } } },
    '/me/review': {
      put: { tags: ['Foydalanuvchi'], summary: 'Sharh qoldirish yoki yangilash', description: 'Har foydalanuvchida bitta sharh.', security: userAuth, requestBody: body(ref('ReviewInput')), responses: { 200: ok(ref('Review')), 400: err('invalid'), 401: err('Token yo‘q') } },
      delete: { tags: ['Foydalanuvchi'], summary: 'Sharhni o‘chirish', security: userAuth, responses: { 200: ok(), 401: err('Token yo‘q') } },
    },

    // ---- Admin: kirish ----
    '/admin/login': { post: { tags: ['Admin: kirish'], summary: 'Xodim kirishi', requestBody: body(ref('AdminLogin')), responses: { 200: ok(ref('StaffSession')), 401: err('invalid_credentials'), 403: err('inactive'), 429: err('Juda ko‘p urinish') } } },
    '/admin/me': { get: { tags: ['Admin: kirish'], summary: 'Joriy xodim', security: staffAuth, responses: { 200: ok(ref('Staff')), 401: err('Token yo‘q') } } },
    '/admin/profile': { put: { tags: ['Admin: kirish'], summary: 'Profilni yangilash', security: staffAuth, requestBody: body(ref('ProfileInput')), responses: { 200: ok(ref('Staff')), 400: err('invalid'), 401: err('Token yo‘q'), 409: err('username_taken') } } },
    '/admin/password': { post: { tags: ['Admin: kirish'], summary: 'Parolni almashtirish', security: staffAuth, requestBody: body(ref('PasswordInput')), responses: { 200: ok(), 400: err('invalid'), 401: err('Token yo‘q yoki joriy parol xato') } } },

    // ---- Admin: statistika va kontent ----
    '/admin/stats': { get: { tags: ['Admin: statistika'], summary: 'Dashboard statistikasi', description: 'Ruxsat: `dashboard.view`. `warehouse` va `finance` bloklari faqat tegishli ruxsat bo‘lsa to‘ladi.', security: staffAuth, responses: { 200: ok(), ...std } } },
    '/admin/content': { get: { tags: ['Admin: kontent'], summary: 'Admin uchun to‘liq kontent', description: 'Yashirin elementlar ham keladi; ruxsati yo‘q bo‘limlar bo‘sh.', security: staffAuth, responses: { 200: ok(), 401: err('Token yo‘q') } } },
    '/admin/settings': { put: { tags: ['Admin: kontent'], summary: 'Sayt sozlamalari', description: 'Ruxsat: `settings.edit`', security: staffAuth, requestBody: body(ref('SettingsInput')), responses: { 200: ok(), ...std } } },
    '/admin/translations/{lang}': { put: { tags: ['Admin: kontent'], summary: 'Matnlarni saqlash', description: 'Ruxsat: `texts.edit`', security: staffAuth, parameters: [{ name: 'lang', in: 'path', required: true, schema: { enum: ['uz', 'ru', 'en'] } }], requestBody: body(ref('TranslationsInput')), responses: { 200: ok(), ...std } } },

    // ---- Admin: to'plamlar ----
    '/admin/{col}': { post: { tags: ['Admin: to‘plamlar'], summary: 'Element yaratish', description: 'Ruxsat: `<col>.create`', security: staffAuth, parameters: [{ name: 'col', in: 'path', required: true, schema: { enum: ['banners', 'products', 'posts', 'news', 'reviews'] } }], requestBody: body(COLLECTION_ITEM), responses: { 200: ok(COLLECTION_ITEM), 404: err('Noma’lum to‘plam'), ...std } } },
    '/admin/{col}/{id}': {
      put: { tags: ['Admin: to‘plamlar'], summary: 'Elementni tahrirlash', description: 'Ruxsat: `<col>.edit`', security: staffAuth, parameters: [{ name: 'col', in: 'path', required: true, schema: { enum: ['banners', 'products', 'posts', 'news', 'reviews'] } }, id], requestBody: body(COLLECTION_ITEM), responses: { 200: ok(COLLECTION_ITEM), 404: err('Topilmadi'), ...std } },
      delete: { tags: ['Admin: to‘plamlar'], summary: 'Elementni o‘chirish', description: 'Ruxsat: `<col>.delete`', security: staffAuth, parameters: [{ name: 'col', in: 'path', required: true, schema: { enum: ['banners', 'products', 'posts', 'news', 'reviews'] } }, id], responses: { 200: ok(), 404: err('Topilmadi'), ...std } },
    },
    '/admin/{col}/reorder': { post: { tags: ['Admin: to‘plamlar'], summary: 'Tartibni saqlash', description: 'Ruxsat: `<col>.edit`', security: staffAuth, parameters: [{ name: 'col', in: 'path', required: true, schema: { enum: ['banners', 'products', 'posts', 'news', 'reviews'] } }], requestBody: body(ref('ReorderInput')), responses: { 200: ok(), ...std } } },

    // ---- Admin: media, xabarlar, foydalanuvchilar, xodimlar ----
    '/admin/media': {
      get: { tags: ['Admin: media'], summary: 'Media ro‘yxati', description: 'Ruxsat: `media.view`', security: staffAuth, responses: { 200: ok(arr(ref('Media'))), ...std } },
      post: {
        tags: ['Admin: media'], summary: 'Rasm/video yuklash', description: 'Ruxsat: `media.create`. Rasm sharp bilan siqiladi.', security: staffAuth,
        requestBody: { required: true, content: { 'multipart/form-data': { schema: obj({
          file: { type: 'string', format: 'binary' }, maxWidth: { type: 'integer', minimum: 64, maximum: 5000, default: 2000 },
          quality: { type: 'integer', minimum: 40, maximum: 100, default: 82 }, format: { enum: ['webp', 'jpeg', 'png', 'original'], default: 'webp' },
        }, ['file']) } } },
        responses: { 200: ok(ref('Media')), 400: err('file_required_or_type_not_allowed'), ...std },
      },
    },
    '/admin/media/{id}': { delete: { tags: ['Admin: media'], summary: 'Mediani o‘chirish', description: 'Ruxsat: `media.delete`', security: staffAuth, parameters: [id], responses: { 200: ok(), 404: err('Topilmadi'), ...std } } },
    '/admin/messages': { get: { tags: ['Admin: xabarlar'], summary: 'Aloqa xabarlari', description: 'Ruxsat: `messages.view`', security: staffAuth, responses: { 200: ok(arr(ref('Message'))), ...std } } },
    '/admin/messages/{id}': {
      patch: { tags: ['Admin: xabarlar'], summary: 'O‘qilgan deb belgilash', description: 'Ruxsat: `messages.edit`', security: staffAuth, parameters: [id], requestBody: body(ref('ReadInput')), responses: { 200: ok(), 404: err('Topilmadi'), ...std } },
      delete: { tags: ['Admin: xabarlar'], summary: 'Xabarni o‘chirish', description: 'Ruxsat: `messages.delete`', security: staffAuth, parameters: [id], responses: { 200: ok(), 404: err('Topilmadi'), ...std } },
    },
    '/admin/users': { get: { tags: ['Admin: foydalanuvchilar'], summary: 'Sayt foydalanuvchilari', description: 'Ruxsat: `users.view`', security: staffAuth, responses: { 200: ok(arr(obj({ id: { type: 'integer' }, username: { type: 'string' }, createdAt: { type: 'string' }, blocked: { type: 'boolean' }, favorites: { type: 'integer' }, reviews: { type: 'integer' } }))), ...std } } },
    '/admin/users/{id}': {
      patch: { tags: ['Admin: foydalanuvchilar'], summary: 'Bloklash / blokdan chiqarish', description: 'Ruxsat: `users.edit`', security: staffAuth, parameters: [id], requestBody: body(ref('BlockInput')), responses: { 200: ok(), 404: err('Topilmadi'), ...std } },
      delete: { tags: ['Admin: foydalanuvchilar'], summary: 'Foydalanuvchini o‘chirish', description: 'Ruxsat: `users.delete`', security: staffAuth, parameters: [id], responses: { 200: ok(), 404: err('Topilmadi'), ...std } },
    },
    '/admin/staff': {
      get: { tags: ['Admin: xodimlar'], summary: 'Xodimlar', description: 'Ruxsat: `staff.view`', security: staffAuth, responses: { 200: ok(arr(ref('Staff'))), ...std } },
      post: { tags: ['Admin: xodimlar'], summary: 'Xodim qo‘shish', description: 'Ruxsat: `staff.create`. `grant`/`revoke` — rol standartiga qo‘shimcha / olib tashlangan ruxsatlar.', security: staffAuth, requestBody: body(ref('StaffCreate')), responses: { 200: ok(ref('Staff')), 400: err('invalid'), 409: err('username_taken'), ...std } },
    },
    '/admin/staff/{id}': {
      put: { tags: ['Admin: xodimlar'], summary: 'Xodimni tahrirlash', description: 'Ruxsat: `staff.edit`. Oxirgi faol Super adminni pasaytirib bo‘lmaydi.', security: staffAuth, parameters: [id], requestBody: body(ref('StaffUpdate')), responses: { 200: ok(ref('Staff')), 404: err('Topilmadi'), 409: err('last_super_admin'), ...std } },
      delete: { tags: ['Admin: xodimlar'], summary: 'Xodimni o‘chirish', description: 'Ruxsat: `staff.delete`', security: staffAuth, parameters: [id], responses: { 200: ok(), 404: err('Topilmadi'), 409: err('last_super_admin'), ...std } },
    },

    // ---- Ombor ----
    ...crud('/admin/warehouses', 'Ombor: omborlar', 'warehouses', 'Warehouse', ref('WarehouseInput')),
    ...crud('/admin/suppliers', 'Ombor: yetkazib beruvchilar', 'suppliers', 'Supplier', ref('SupplierInput')),
    '/admin/catalog': {
      get: { tags: ['Ombor: katalog'], summary: 'Katalog + ombor bo‘yicha qoldiq', description: 'Ruxsat: `catalog.view`. Qoldiq hujjatlardan hisoblanadi.', security: staffAuth, responses: { 200: ok(arr(ref('CatalogItem'))), ...std } },
      post: { tags: ['Ombor: katalog'], summary: 'Tovar qo‘shish', description: 'Ruxsat: `catalog.create`', security: staffAuth, requestBody: body(ref('CatalogInput')), responses: { 200: ok(ref('CatalogItem')), 400: err('invalid | invalid_barcode'), 409: err('sku_taken | barcode_taken'), ...std } },
    },
    '/admin/catalog/lookup': { get: { tags: ['Ombor: katalog'], summary: 'Shtrix kod yoki SKU bo‘yicha qidirish (skaner)', description: 'Ruxsat: `catalog.view`', security: staffAuth, parameters: [{ ...q('code', 'Shtrix kod yoki SKU'), required: true }], responses: { 200: ok(ref('CatalogItem')), 404: err('not_found'), ...std } } },
    '/admin/catalog/{id}/history': { get: { tags: ['Ombor: katalog'], summary: 'Tovar bo‘yicha oxirgi 50 harakat', description: 'Ruxsat: `catalog.view`', security: staffAuth, parameters: [id], responses: { 200: ok(arr({ type: 'object' })), ...std } } },
    '/admin/catalog/{id}': {
      put: { tags: ['Ombor: katalog'], summary: 'Tovarni tahrirlash', description: 'Ruxsat: `catalog.edit`', security: staffAuth, parameters: [id], requestBody: body(ref('CatalogInput')), responses: { 200: ok(ref('CatalogItem')), 404: err('Topilmadi'), 409: err('sku_taken | barcode_taken'), ...std } },
      delete: { tags: ['Ombor: katalog'], summary: 'Tovarni o‘chirish', description: 'Ruxsat: `catalog.delete`. Harakati bor tovar o‘chirilmaydi.', security: staffAuth, parameters: [id], responses: { 200: ok(), 404: err('Topilmadi'), 409: err('Ishlatilmoqda'), ...std } },
    },
    '/admin/movements': {
      get: {
        tags: ['Ombor: harakatlar'], summary: 'Harakatlar jurnali', description: 'Ruxsat: `movements.view`. Eng yangisi birinchi.', security: staffAuth,
        parameters: [q('type', 'Hujjat turi', { enum: [...MOVEMENT_TYPES] }), q('status', 'Holat', { enum: ['posted', 'cancelled'] }), q('warehouseId', 'Ombor', { type: 'integer' }), q('itemId', 'Tovar', { type: 'integer' }), q('from', 'Boshlanish sanasi (YYYY-MM-DD)'), q('to', 'Tugash sanasi (YYYY-MM-DD)'), q('q', 'Raqam, kontragent, izoh yoki muallif bo‘yicha qidirish'), q('limit', 'Maks. 500', { type: 'integer', default: 50 }), q('offset', 'Siljish', { type: 'integer', default: 0 })],
        responses: { 200: ok(obj({ total: { type: 'integer' }, rows: arr(ref('Movement')) })), ...std },
      },
      post: { tags: ['Ombor: harakatlar'], summary: 'Hujjat yaratish va o‘tkazish', description: 'Ruxsat: `movements.create`. Yetmaydigan chiqim `409 insufficient_stock` bilan rad etiladi.', security: staffAuth, requestBody: body(ref('MovementInput')), responses: { 200: ok(ref('Movement')), 400: err('invalid_type | warehouse_not_found | invalid_lines | invalid_qty | ...'), 409: err('insufficient_stock'), ...std } },
    },
    '/admin/movements/{id}/cancel': { post: { tags: ['Ombor: harakatlar'], summary: 'Hujjatni bekor qilish', description: 'Ruxsat: `movements.delete`. Hujjat o‘chirilmaydi, sabab bilan bekor qilinadi. Qoldiq manfiy bo‘lib qolsa — 409.', security: staffAuth, parameters: [id], requestBody: body(ref('CancelInput')), responses: { 200: ok(ref('Movement')), 404: err('Topilmadi'), 409: err('insufficient_stock | already_cancelled'), ...std } } },
    '/admin/inventory/apply': { post: { tags: ['Ombor: harakatlar'], summary: 'Inventarizatsiyani tasdiqlash', description: 'Ruxsat: `inventory.create`. Sanalgan va tizim qoldig‘i farqi uchun ADJUSTMENT hujjati yaratiladi.', security: staffAuth, requestBody: body(ref('InventoryInput')), responses: { 200: ok(ref('Movement')), 400: err('no_differences'), ...std } } },

    // ---- Buxgalteriya ----
    '/admin/finance/transactions': {
      get: { tags: ['Buxgalteriya'], summary: 'Kassa operatsiyalari', description: 'Ruxsat: `finance.view`', security: staffAuth, parameters: [q('type', 'income | expense'), q('category', 'Kategoriya'), q('from', 'YYYY-MM-DD'), q('to', 'YYYY-MM-DD')], responses: { 200: ok(arr(ref('Transaction'))), ...std } },
      post: { tags: ['Buxgalteriya'], summary: 'Operatsiya qo‘shish', description: 'Ruxsat: `finance.create`', security: staffAuth, requestBody: body(ref('TransactionInput')), responses: { 200: ok(ref('Transaction')), 400: err('invalid | supplier_required'), ...std } },
    },
    '/admin/finance/transactions/{id}': {
      put: { tags: ['Buxgalteriya'], summary: 'Operatsiyani tahrirlash', description: 'Ruxsat: `finance.edit`', security: staffAuth, parameters: [id], requestBody: body(ref('TransactionInput')), responses: { 200: ok(ref('Transaction')), 404: err('Topilmadi'), ...std } },
      delete: { tags: ['Buxgalteriya'], summary: 'Operatsiyani o‘chirish', description: 'Ruxsat: `finance.delete`', security: staffAuth, parameters: [id], responses: { 200: ok(), 404: err('Topilmadi'), ...std } },
    },
    '/admin/finance/payables': { get: { tags: ['Buxgalteriya'], summary: 'Yetkazib beruvchilarga qarz', description: 'Ruxsat: `finance.view`. Qarz = olingan tovar qiymati − to‘langan summa.', security: staffAuth, responses: { 200: ok(), ...std } } },
    '/admin/reports': { get: { tags: ['Buxgalteriya'], summary: 'Hisobot (foyda, kunlik grafik, kategoriyalar, qoldiq qiymati)', description: 'Ruxsat: `reports.view`. Standart davr — joriy oy.', security: staffAuth, parameters: [q('from', 'YYYY-MM-DD'), q('to', 'YYYY-MM-DD')], responses: { 200: ok(), ...std } } },
  },
} as const

