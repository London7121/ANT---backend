import { Router } from 'express'
import type { StaffUser } from '@ant/shared/roles'
import { checkPassword, hashPassword } from '@/db/password'
import { getDb, save } from '@/db/store'
import type { Staff } from '@/db/types'
import { signToken } from '@/middleware/auth'
import { rateLimit } from '@/middleware/rateLimit'
import { AdminLogin, PasswordInput, ProfileInput } from '@/schemas'

/** Parolsiz ko'rinish — frontendga faqat shu yuboriladi. */
export const toStaffUser = ({ hash: _hash, ...rest }: Staff): StaffUser => rest

/** Ochiq: kirish. */
export const loginRouter = Router()
loginRouter.post('/login', rateLimit(10, 15 * 60 * 1000), (req, res) => {
  const { username, password } = AdminLogin.parse(req.body ?? {})
  const staff = getDb().staff.find((s) => s.username === username)
  if (!staff || !checkPassword(password, staff.hash)) return res.status(401).json({ error: 'invalid_credentials' })
  if (!staff.active) return res.status(403).json({ error: 'inactive' })
  res.json({ token: signToken('staff', staff.id), user: toStaffUser(staff) })
})

/** Himoyalangan: o'z profili. */
export const accountRouter = Router()
accountRouter.get('/me', (req, res) => res.json(toStaffUser(req.staff!)))

accountRouter.put('/profile', (req, res) => {
  const me = req.staff!
  const { name, username } = ProfileInput.parse(req.body ?? {})
  if (typeof username === 'string' && username.trim() && username.trim() !== me.username) {
    const next = username.trim()
    if (next.length < 3 || next.length > 32 || getDb().staff.some((s) => s.username === next)) return res.status(409).json({ error: 'username_invalid_or_taken' })
    me.username = next
  }
  if (name !== undefined) me.name = name
  save()
  res.json(toStaffUser(me))
})

accountRouter.post('/password', (req, res) => {
  const { current, next } = PasswordInput.parse(req.body ?? {})
  const me = req.staff!
  if (!checkPassword(current, me.hash)) return res.status(400).json({ error: 'wrong_password' })
  if (next.length < 6) return res.status(400).json({ error: 'too_short' })
  me.hash = hashPassword(next)
  save()
  res.json({ ok: true })
})
