import { Router } from 'express'
import { can, type Module, type Permission } from '@ant/shared/roles'
import { getDb, save } from '@/db/store'
import { requirePermission } from '@/middleware/auth'
import { SettingsInput, TranslationsInput } from '@/schemas'

export const contentRouter = Router()

/**
 * Admin ko'radigan ma'lumot: faqat rolga ruxsat etilgan bo'limlar to'ldiriladi
 * (masalan, ombor xodimi xabarlarni ko'rmaydi). Parollar hech qachon yuborilmaydi.
 */
contentRouter.get('/content', (req, res) => {
  const db = getDb()
  const staff = req.staff!
  const allow = <T>(module: Module, value: T[]): T[] => (can(staff, `${module}.view` as Permission) ? value : [])
  res.json({
    settings: db.settings,
    banners: allow('banners', db.banners),
    products: allow('products', db.products),
    posts: allow('posts', db.posts),
    news: allow('news', db.news),
    reviews: allow('reviews', db.reviews),
    media: allow('media', db.media),
    messages: allow('messages', db.messages),
    translations: can(staff, 'texts.view') ? db.translations : { uz: {}, ru: {}, en: {} },
  })
})

contentRouter.put('/settings', requirePermission('settings.edit'), (req, res) => {
  const db = getDb()
  const patch = SettingsInput.parse(req.body ?? {}) as Record<string, unknown>
  const current = db.settings as unknown as Record<string, unknown>
  // Bo'lim (brand, seo, ...) ichida yuborilmagan maydonlar saqlanib qoladi
  for (const [key, value] of Object.entries(patch)) {
    const old = current[key]
    current[key] = value && typeof value === 'object' && !Array.isArray(value) && old && typeof old === 'object' ? { ...old, ...value } : value
  }
  save()
  res.json(db.settings)
})

contentRouter.put('/translations/:lang', requirePermission('texts.edit'), (req, res) => {
  const lang = req.params.lang
  if (lang !== 'uz' && lang !== 'ru' && lang !== 'en') return res.status(400).json({ error: 'invalid_lang' })
  getDb().translations[lang] = TranslationsInput.parse(req.body ?? {})
  save()
  res.json(getDb().translations[lang])
})
