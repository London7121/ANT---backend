import { Router } from 'express'
import { hashPassword } from '@/db/password'
import { getDb, nextId, save } from '@/db/store'
import type { Staff } from '@/db/types'
import { requirePermission } from '@/middleware/auth'
import { StaffCreate, StaffUpdate } from '@/schemas'
import { toStaffUser } from './auth'

/** Xodimlarni boshqarish (faqat SUPER_ADMIN — ruxsat 'staff' moduli orqali). */
export const staffRouter = Router()

const activeSupers = (list: Staff[]) => list.filter((s) => s.role === 'SUPER_ADMIN' && s.active)

staffRouter.get('/', requirePermission('staff.view'), (_req, res) => res.json(getDb().staff.map(toStaffUser)))

staffRouter.post('/', requirePermission('staff.create'), (req, res) => {
  const { username, name, password, role, grant, revoke } = StaffCreate.parse(req.body ?? {})
  const db = getDb()
  if (db.staff.some((s) => s.username === username)) return res.status(409).json({ error: 'username_taken' })
  const staff: Staff = { id: nextId(db.staff), username, name, role, grant: [...new Set(grant)], revoke: [...new Set(revoke)], active: true, hash: hashPassword(password), createdAt: new Date().toISOString() }
  db.staff.push(staff)
  save()
  res.json(toStaffUser(staff))
})

staffRouter.put('/:id', requirePermission('staff.edit'), (req, res) => {
  const db = getDb()
  const target = db.staff.find((s) => s.id === Number(req.params.id))
  if (!target) return res.status(404).json({ error: 'not_found' })
  const { name, role, active, password, grant, revoke } = StaffUpdate.parse(req.body ?? {})

  const next = { ...target }
  if (name !== undefined) next.name = name
  if (role) next.role = role
  if (active !== undefined) next.active = active
  if (grant) next.grant = [...new Set(grant)]
  if (revoke) next.revoke = [...new Set(revoke)]
  // Tizimda kamida bitta faol SUPER_ADMIN qolishi shart
  const after = db.staff.map((s) => (s.id === target.id ? next : s))
  if (activeSupers(after).length === 0) return res.status(400).json({ error: 'last_super_admin' })

  Object.assign(target, next)
  if (password && password.length >= 6) target.hash = hashPassword(password)
  save()
  res.json(toStaffUser(target))
})

staffRouter.delete('/:id', requirePermission('staff.delete'), (req, res) => {
  const db = getDb()
  const id = Number(req.params.id)
  if (id === req.staff!.id) return res.status(400).json({ error: 'cannot_delete_self' })
  const rest = db.staff.filter((s) => s.id !== id)
  if (activeSupers(rest).length === 0) return res.status(400).json({ error: 'last_super_admin' })
  db.staff = rest
  save()
  res.json({ ok: true })
})
