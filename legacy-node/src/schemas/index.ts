/**
 * API sxemalari — yagona manba: so'rovni tekshirish (zod) + Swagger hujjati (docs/openapi.ts).
 * `.meta({ id })` berilgan sxemalar OpenAPI `components.schemas` ga tushadi.
 * Qoida: xato kodi kerak bo'lsa `.pipe(... 'kod')` / `message: 'kod'` — errorHandler uni `{ error: 'kod' }` qilib qaytaradi.
 */
import { z } from 'zod'
import { isPermission, ROLES } from '@ant/shared/roles'
import {
  EXPENSE_CATEGORIES, INCOME_CATEGORIES, ISSUE_REASONS, MOVEMENT_TYPES, PAYMENT_METHODS, UNITS, WRITE_OFF_REASONS,
  type CatalogItem, type Movement, type Supplier, type Transaction, type Warehouse,
} from '@ant/shared/warehouse'

// ---- Yordamchilar ----
/** Matn: kesadi va tozalaydi; noto'g'ri tur → bo'sh satr (xato bermaydi). */
const text = (max: number) => z.string().default('').transform((s) => s.trim().slice(0, max)).catch('').meta({ maxLength: max })
/** Majburiy matn: bo'sh bo'lsa `invalid`. */
const required = (max: number, code = 'invalid') => z.string().transform((s) => s.trim().slice(0, max)).pipe(z.string().min(1, code)).meta({ minLength: 1, maxLength: max })
const money = z.number().default(0).transform((v) => (v >= 0 ? Math.round(v * 100) / 100 : 0)).catch(0).meta({ minimum: 0 })
const flag = z.boolean().catch(true) // yuborilmasa — faol
const date = z.string().meta({ format: 'date' })
const dateOr = (fallback: () => string) => date.regex(/^\d{4}-\d{2}-\d{2}$/).catch(fallback)
const idList = z.array(z.number().int())
const ref = z.object({ id: z.number().int(), name: z.string() })
const stamp = z.string().meta({ format: 'date-time' })

export const PHONE_RE = /^[+\d][\d\s()-]{8,}$/
export const USERNAME_RE = /^[a-zA-Z0-9_.-]{3,32}$/
export const BARCODE_RE = /^[A-Za-z0-9-]{4,32}$/

// ---- Umumiy ----
export const Ok = z.object({ ok: z.boolean() }).meta({ id: 'Ok' })
export const ErrorBody = z.object({ error: z.string().meta({ examples: ['invalid'] }), detail: z.unknown().optional() }).meta({ id: 'Error' })
export const Loc = z.object({ uz: z.string(), ru: z.string(), en: z.string() }).meta({ id: 'Loc' })
export const Role = z.enum(ROLES).meta({ id: 'Role' })
export const Permission = z.string().meta({ id: 'Permission', description: '`modul.amal`, masalan `catalog.create`' })
const permissions = z.array(Permission).transform((a) => a.filter(isPermission)).catch([])

// ---- Kirish ----
export const AdminLogin = z.object({ username: z.string(), password: z.string() }).meta({ id: 'AdminLogin' })
export const SiteRegister = z.object({
  username: z.string().trim().regex(USERNAME_RE).meta({ description: '3–32 belgi: harf, raqam, _ . -' }),
  password: z.string().min(4).max(200),
}).meta({ id: 'SiteRegister' })
export const SiteLogin = z.object({ username: z.string().trim(), password: z.string() }).meta({ id: 'SiteLogin' })

export const StaffSchema = z.object({
  id: z.number().int(), username: z.string(), name: z.string(), role: Role, grant: z.array(Permission), revoke: z.array(Permission),
  active: z.boolean(), createdAt: stamp,
}).meta({ id: 'Staff' })
export const StaffSession = z.object({ token: z.string(), user: StaffSchema }).meta({ id: 'StaffSession' })
export const SiteSession = z.object({
  token: z.string(), user: z.object({ id: z.number().int(), username: z.string(), createdAt: stamp }), favorites: idList,
}).meta({ id: 'SiteSession' })

export const ProfileInput = z.object({ name: text(80).optional(), username: z.string().optional() }).meta({ id: 'ProfileInput' })
export const PasswordInput = z.object({ current: z.string(), next: z.string().meta({ minLength: 6 }) }).meta({ id: 'PasswordInput' })

export const StaffCreate = z.object({
  username: z.string().trim().min(3, 'invalid'), name: text(80), password: z.string().min(6, 'invalid'), role: Role, grant: permissions, revoke: permissions,
}).meta({ id: 'StaffCreate' })
export const StaffUpdate = z.object({
  name: text(80).optional(), role: Role.optional(), active: z.boolean().optional(),
  password: z.string().optional().meta({ description: 'Bo‘sh yoki 6 belgidan qisqa bo‘lsa e’tiborga olinmaydi' }),
  grant: permissions.optional(), revoke: permissions.optional(),
}).meta({ id: 'StaffUpdate' })

// ---- Sayt ----
export const ContactInput = z.object({
  name: required(120), phone: z.string().trim().regex(PHONE_RE, 'invalid').meta({ maxLength: 40 }), message: z.string().default('').transform((s) => s.slice(0, 3000)).catch('').meta({ maxLength: 3000 }),
}).meta({ id: 'ContactInput' })
export const FavoritesInput = z.object({ ids: idList }).meta({ id: 'FavoritesInput' })
export const ReviewInput = z.object({
  rating: z.number().int().min(1).max(5), text: z.string().trim().min(1).max(1000), lang: z.enum(['uz', 'ru', 'en']).catch('uz'),
}).meta({ id: 'ReviewInput' })
export const ReviewSchema = z.object({
  id: z.number().int(), name: z.string(), rating: z.number().int(), text: Loc, visible: z.boolean(), userId: z.number().int().optional(), createdAt: stamp.optional(),
}).meta({ id: 'Review' })
export const BlockInput = z.object({ blocked: z.boolean() }).meta({ id: 'BlockInput' })
export const ReadInput = z.object({ read: z.boolean() }).meta({ id: 'ReadInput' })
export const ReorderInput = z.object({ ids: idList }).meta({ id: 'ReorderInput' })

export const Message = z.object({ id: z.number().int(), name: z.string(), phone: z.string(), message: z.string(), read: z.boolean(), createdAt: stamp }).meta({ id: 'Message' })
export const Media = z.object({
  id: z.number().int(), type: z.enum(['image', 'video']), name: z.string(), url: z.string(), thumb: z.string().optional(),
  width: z.number().optional(), height: z.number().optional(), size: z.number(), createdAt: stamp,
}).meta({ id: 'Media' })
// ---- Sayt to'plamlari (banners, products, posts, news, reviews) ----
/** Yetishmagan til bo'sh qator bo'ladi — to'liq bo'lmagan so'rov saytni buzmaydi. */
const locField = z.object({ uz: z.string().default(''), ru: z.string().default(''), en: z.string().default('') })
  .default({ uz: '', ru: '', en: '' }).meta({ description: 'Ko‘p tilli matn: uz / ru / en' })
/** Sozlamalarda: default YO'Q — yuborilmagan til/bo'lim mavjud qiymatni o'zgartirmasligi kerak. */
const locPatch = z.object({ uz: z.string(), ru: z.string(), en: z.string() }).partial().meta({ description: 'Ko‘p tilli matn: uz / ru / en' })
const str = z.string().default('')
const strList = z.array(z.string()).default([])
const visible = z.boolean().default(true).meta({ description: 'false bo‘lsa saytda ko‘rinmaydi' })
const today = () => new Date().toISOString().slice(0, 10)
/** `looseObject` — noma'lum maydonlar saqlanadi (masalan, sharhdagi userId/createdAt). Javobda `id` qo'shiladi. */
const item = <T extends z.ZodRawShape>(shape: T) => z.looseObject(shape)

export const BannerSchema = item({ image: str, accent: z.string().default('#B59E67'), title: locField, visible }).meta({ id: 'Banner' })
export const ProductSchema = item({
  name: locField, description: locField, sizes: strList, colors: strList, image: str, images: strList, isNew: z.boolean().default(false), visible,
}).meta({ id: 'Product' })
export const PostSchema = item({
  type: z.enum(['video', 'photo', 'post']).default('photo'), title: locField, text: locField, image: str, video: str,
  height: z.number().default(0).meta({ description: 'Masonry kartochka balandligi (px); 0 — avtomatik' }), date: date.default(today), visible,
}).meta({ id: 'Post' })
export const NewsSchema = item({ title: locField, text: locField, body: locField, image: str, gallery: strList, date: date.default(today), visible }).meta({ id: 'NewsItem' })
export const ReviewAdminSchema = item({
  name: str, rating: z.number().int().min(1).max(5).default(5), text: locField, avatar: z.string().optional(), visible,
}).meta({ id: 'ReviewAdmin', description: 'Admin tahriri: foydalanuvchi sharhidagi userId/createdAt saqlanadi' })

export const COLLECTION_SCHEMAS = {
  banners: BannerSchema, products: ProductSchema, posts: PostSchema, news: NewsSchema, reviews: ReviewAdminSchema,
} as const

// ---- Sayt sozlamalari: qisman yangilash (yuborilgan bo'limlar almashtiriladi) ----
const sectionKeys = ['hero', 'marquee', 'stats', 'products', 'promo', 'feed', 'news', 'reviews', 'contact'] as const
export const SettingsInput = z.looseObject({
  brand: z.looseObject({ name: z.string().min(1), logo: z.string().optional() }).partial(),
  phone: z.string(), email: z.string(), telegram: z.string(), instagram: z.string(),
  address: locPatch, hours: locPatch,
  stats: z.looseObject({ clients: z.number(), products: z.number(), years: z.number(), cities: z.number() }).partial().meta({ description: 'clients/products saytda haqiqiy ma’lumotdan hisoblanadi' }),
  theme: z.looseObject({ accent: z.string(), accentDark: z.string() }).partial(),
  seo: z.looseObject({ siteUrl: z.string(), title: locPatch, description: locPatch, ogImage: z.string().optional() }).partial(),
  sections: z.array(z.object({ key: z.enum(sectionKeys), visible: z.boolean() })).meta({ description: 'Bo‘limlar tartibi va ko‘rinishi' }),
  hero: z.looseObject({ slideMs: z.number().min(1500).max(30000) }).partial(),
  promo: z.looseObject({ image: z.string().optional(), href: z.string() }).partial(),
}).partial().meta({ id: 'SettingsInput' })

export const TranslationsInput = z.record(z.string(), z.union([z.string(), z.array(z.string())])).meta({ id: 'TranslationsInput', description: 'Kalit → matn (yoki matnlar ro‘yxati)' })

// ---- Ombor ----
export const WarehouseSchema = z.object({ id: z.number().int(), name: z.string(), address: z.string(), note: z.string(), active: z.boolean() }).meta({ id: 'Warehouse' })
export const WarehouseInput = z.object({ name: required(80), address: text(200), note: text(300), active: flag }).meta({ id: 'WarehouseInput' })
export const SupplierSchema = z.object({ id: z.number().int(), name: z.string(), phone: z.string(), address: z.string(), note: z.string(), active: z.boolean() }).meta({ id: 'Supplier' })
export const SupplierInput = z.object({ name: required(120), phone: text(40), address: text(200), note: text(300), active: flag }).meta({ id: 'SupplierInput' })

const unit = z.enum(UNITS)
export const CatalogInput = z.object({
  sku: required(40), name: required(160), category: text(60), unit: unit.catch('pcs'),
  barcodes: z.array(z.string()).catch([])
    .transform((a) => [...new Set(a.map((x) => x.trim().slice(0, 32)).filter(Boolean))])
    .pipe(z.array(z.string().regex(BARCODE_RE, 'invalid_barcode')))
    .meta({ description: 'Bir nechta shtrix kod (4–32 belgi: harf, raqam, -); takrorlanmasligi kerak' }),
  cost: money, price: money, minStock: money.meta({ description: 'Shundan kam qolsa ogohlantirish' }),
  image: text(300), note: text(500), active: flag,
}).meta({ id: 'CatalogInput' })
export const CatalogSchema = z.object({
  id: z.number().int(), sku: z.string(), name: z.string(), category: z.string(), unit, barcodes: z.array(z.string()),
  cost: z.number(), price: z.number(), minStock: z.number(), image: z.string(), note: z.string(), active: z.boolean(), createdAt: stamp,
}).meta({ id: 'CatalogItemBase' })
export const CatalogWithStock = CatalogSchema.extend({
  stock: z.record(z.string(), z.number()).meta({ description: 'ombor ID → qoldiq' }), stockTotal: z.number(),
}).meta({ id: 'CatalogItem' })

export const MovementLine = z.object({
  itemId: z.number().int(),
  qty: z.number().meta({ description: 'Musbat son. ADJUSTMENT da ishorali farq (+/−)' }),
  price: z.number().optional().meta({ description: 'Kirimda majburiy; boshqa hujjatlarda katalogdagi tannarx' }),
}).meta({ id: 'MovementLine' })
export const MovementInput = z.object({
  type: z.enum(MOVEMENT_TYPES).meta({ description: 'RECEIPT kirim · ISSUE chiqim · TRANSFER ko‘chirish · WRITE_OFF hisobdan chiqarish · ADJUSTMENT tuzatish' }),
  date: date.optional(), warehouseId: z.number().int(),
  toWarehouseId: z.number().int().optional().meta({ description: 'Faqat TRANSFER' }), supplierId: z.number().int().optional().meta({ description: 'Faqat RECEIPT' }),
  counterparty: z.string().optional().meta({ description: 'ISSUE: kimga/qayerga' }),
  reason: z.string().optional().meta({ description: `ISSUE: ${ISSUE_REASONS.join(', ')} · WRITE_OFF: ${WRITE_OFF_REASONS.join(', ')}` }),
  lines: z.array(MovementLine).min(1).max(200), note: z.string().optional(),
}).meta({ id: 'MovementInput', description: 'Domen tekshiruvi services/movements.ts da (xato kodlari: invalid_type, insufficient_stock, ...)' })
export const MovementSchema = z.object({
  id: z.number().int(), number: z.string().meta({ examples: ['KR-00012'] }), type: z.enum(MOVEMENT_TYPES), date: date, warehouseId: z.number().int(),
  toWarehouseId: z.number().int().optional(), supplierId: z.number().int().optional(), counterparty: z.string(), reason: z.string(),
  lines: z.array(z.object({ itemId: z.number().int(), qty: z.number(), price: z.number() })), note: z.string(), status: z.enum(['posted', 'cancelled']),
  createdBy: ref, createdAt: stamp, cancelledBy: ref.optional(), cancelledAt: stamp.optional(), cancelReason: z.string().optional(),
}).meta({ id: 'Movement' })
export const CancelInput = z.object({ reason: z.string({ error: 'reason_required' }).trim().min(1, 'reason_required') }).meta({ id: 'CancelInput' })
export const InventoryInput = z.object({
  warehouseId: z.number().int(),
  counts: z.array(z.object({ itemId: z.number().int(), counted: z.number().min(0).meta({ description: 'Sanalgan haqiqiy qoldiq' }) })),
  note: text(500),
}).meta({ id: 'InventoryInput' })

// ---- Buxgalteriya ----
const categories = [...INCOME_CATEGORIES, ...EXPENSE_CATEGORIES] as const
export const TransactionInput = z.object({
  type: z.enum(['income', 'expense']),
  category: z.enum(categories).meta({ description: `income: ${INCOME_CATEGORIES.join(', ')} · expense: ${EXPENSE_CATEGORIES.join(', ')}` }),
  amount: z.number().transform((v) => Math.round(v * 100) / 100).pipe(z.number().positive()).meta({ exclusiveMinimum: 0 }),
  date: dateOr(() => new Date().toISOString().slice(0, 10)), method: z.enum(PAYMENT_METHODS).catch('cash'),
  counterparty: text(160), supplierId: z.number().int().optional().meta({ description: 'category=supplier_payment bo‘lsa majburiy' }), note: text(500),
}).refine(
  (t) => (t.type === 'income' ? (INCOME_CATEGORIES as readonly string[]) : (EXPENSE_CATEGORIES as readonly string[])).includes(t.category),
  { message: 'invalid', path: ['category'] },
).meta({ id: 'TransactionInput' })
export const TransactionSchema = z.object({
  id: z.number().int(), date, type: z.enum(['income', 'expense']), category: z.string(), amount: z.number(), method: z.enum(PAYMENT_METHODS),
  counterparty: z.string(), supplierId: z.number().int().optional(), note: z.string(), createdBy: ref, createdAt: stamp,
}).meta({ id: 'Transaction' })

// ---- Sxema ↔ umumiy tiplar mosligi (kompilyatsiya vaqtida) ----
type Same<A, B> = [A] extends [B] ? ([B] extends [A] ? true : never) : never
const _check: [
  Same<z.infer<typeof WarehouseSchema>, Warehouse>,
  Same<z.infer<typeof SupplierSchema>, Supplier>,
  Same<z.infer<typeof CatalogSchema>, CatalogItem>,
  Same<z.infer<typeof MovementSchema>, Movement>,
  Same<z.infer<typeof TransactionSchema>, Transaction>,
] = [true, true, true, true, true]
void _check
