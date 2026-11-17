import { Router } from 'express'
import { getDb, save } from '@/db/store'
import { requirePermission } from '@/middleware/auth'
import { ReadInput } from '@/schemas'

export const messagesRouter = Router()

messagesRouter.get('/', requirePermission('messages.view'), (_req, res) => res.json(getDb().messages))

messagesRouter.patch('/:id', requirePermission('messages.edit'), (req, res) => {
  const message = getDb().messages.find((m) => m.id === Number(req.params.id))
  if (!message) return res.status(404).json({ error: 'not_found' })
  message.read = ReadInput.parse(req.body ?? {}).read
  save()
  res.json(message)
})

messagesRouter.delete('/:id', requirePermission('messages.delete'), (req, res) => {
  const db = getDb()
  db.messages = db.messages.filter((m) => m.id !== Number(req.params.id))
  save()
  res.json({ ok: true })
})
