import { Router } from 'express'
import { getDb, save } from '@/db/store'
import { requirePermission } from '@/middleware/auth'
import { BlockInput } from '@/schemas'

/** Landing foydalanuvchilari: ro'yxat, bloklash, o'chirish. */
export const usersRouter = Router()

usersRouter.get('/', requirePermission('users.view'), (_req, res) => {
  const { users, reviews } = getDb()
  res.json(users.map((u) => ({ id: u.id, username: u.username, createdAt: u.createdAt, blocked: u.blocked, favorites: u.favorites.length, reviews: reviews.filter((r) => r.userId === u.id).length })))
})

usersRouter.patch('/:id', requirePermission('users.edit'), (req, res) => {
  const user = getDb().users.find((u) => u.id === Number(req.params.id))
  if (!user) return res.status(404).json({ error: 'not_found' })
  user.blocked = BlockInput.parse(req.body ?? {}).blocked
  save()
  res.json({ id: user.id, blocked: user.blocked })
})

/** Foydalanuvchi bilan birga uning sharhlari ham o'chiriladi. */
usersRouter.delete('/:id', requirePermission('users.delete'), (req, res) => {
  const db = getDb()
  const id = Number(req.params.id)
  db.users = db.users.filter((u) => u.id !== id)
  db.reviews = db.reviews.filter((r) => r.userId !== id)
  save()
  res.json({ ok: true })
})
