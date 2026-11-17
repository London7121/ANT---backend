import { Router } from 'express'
import { getDb, nextId, save } from '@/db/store'
import { rateLimit } from '@/middleware/rateLimit'
import { ContactInput } from '@/schemas'

export const publicRouter = Router()

const onlyVisible = <T extends { visible?: boolean }>(items: T[]) => items.filter((x) => x.visible !== false)

/** Reyting va raqamlar qo'lda emas, haqiqiy ma'lumotlardan hisoblanadi. */
function liveSettings() {
  const { settings, reviews, products, users } = getDb()
  const shown = onlyVisible(reviews)
  const average = shown.length ? shown.reduce((sum, r) => sum + r.rating, 0) / shown.length : 0
  return {
    ...settings,
    rating: Math.round(average * 10) / 10,
    reviewsCount: shown.length,
    stats: { ...settings.stats, clients: users.filter((u) => !u.blocked).length, products: onlyVisible(products).length },
  }
}

/** Landing uchun barcha ochiq kontent (yashirin elementlarsiz). */
publicRouter.get('/content', (_req, res) => {
  const { banners, products, posts, news, reviews, translations } = getDb()
  res.setHeader('Cache-Control', 'no-cache')
  res.json({
    settings: liveSettings(),
    banners: onlyVisible(banners),
    products: onlyVisible(products),
    posts: onlyVisible(posts),
    news: onlyVisible(news),
    reviews: onlyVisible(reviews).toSorted((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? '')),
    translations,
  })
})

/** Aloqa formasi. */
publicRouter.post('/contact', rateLimit(5, 60 * 60 * 1000), (req, res) => {
  const { name, phone, message } = ContactInput.parse(req.body ?? {})
  const db = getDb()
  db.messages.unshift({
    id: nextId(db.messages),
    name,
    phone: phone.slice(0, 40),
    message,
    read: false,
    createdAt: new Date().toISOString(),
  })
  save()
  res.json({ ok: true })
})
