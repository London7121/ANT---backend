import { Router } from 'express'
import type { Review } from '@ant/shared/types'
import { getDb, nextId, save } from '@/db/store'
import { requireUser } from '@/middleware/auth'
import { rateLimit } from '@/middleware/rateLimit'
import { FavoritesInput, ReviewInput } from '@/schemas'
import { publicUser } from './auth'

/** Kirgan foydalanuvchi: profil, sevimlilar, o'z sharhi. */
export const meRouter = Router()
meRouter.use(requireUser)

const reviewsOf = (userId: number) => getDb().reviews.filter((r) => r.userId === userId)

meRouter.get('/', (req, res) => {
  const user = req.siteUser!
  res.json({ user: publicUser(user), favorites: user.favorites, reviews: reviewsOf(user.id) })
})

meRouter.put('/favorites', (req, res) => {
  const db = getDb()
  const { ids } = FavoritesInput.parse(req.body ?? {})
  const valid = new Set(db.products.map((p) => p.id))
  req.siteUser!.favorites = [...new Set(ids.filter((x) => valid.has(x)))]
  save()
  res.json({ favorites: req.siteUser!.favorites })
})

/** Har foydalanuvchining bitta sharhi bor: qayta yuborilsa, yangilanadi. Admin yashirgan bo'lsa, yashirin qoladi. */
meRouter.put('/review', rateLimit(20, 60 * 60 * 1000), (req, res) => {
  const user = req.siteUser!
  const { rating, text, lang } = ReviewInput.parse(req.body ?? {})

  const db = getDb()
  const fields = { rating, text: { uz: '', ru: '', en: '', [lang]: text } }
  const existing = db.reviews.find((r) => r.userId === user.id)
  let review: Review
  if (existing) {
    Object.assign(existing, fields)
    review = existing
  } else {
    review = { id: nextId(db.reviews), name: user.username, visible: true, userId: user.id, createdAt: new Date().toISOString(), ...fields }
    db.reviews.push(review)
  }
  save()
  res.json(review)
})

meRouter.delete('/review', (req, res) => {
  const db = getDb()
  db.reviews = db.reviews.filter((r) => r.userId !== req.siteUser!.id)
  save()
  res.json({ ok: true })
})
