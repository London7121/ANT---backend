import { Router } from 'express'
import type { Transaction } from '@ant/shared/warehouse'
import { getDb, nextId, save } from '@/db/store'
import { requirePermission } from '@/middleware/auth'
import { TransactionInput } from '@/schemas'
import { payables, report } from '@/services/finance'

export const financeRouter = Router()
export const reportsRouter = Router()

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/
const today = () => new Date().toISOString().slice(0, 10)

/** Sxema (TransactionInput) tozalaydi; yetkazib beruvchi bazada borligi bu yerda tekshiriladi. */
function shape(body: unknown): { data: Omit<Transaction, 'id' | 'createdBy' | 'createdAt'> } | { error: string } {
  const { supplierId, ...data } = TransactionInput.parse(body ?? {})
  if (data.category !== 'supplier_payment') return { data }
  if (!getDb().suppliers.some((s) => s.id === supplierId)) return { error: 'supplier_required' }
  return { data: { ...data, supplierId } }
}

financeRouter.get('/transactions', requirePermission('finance.view'), (req, res) => {
  const q = req.query as Record<string, string | undefined>
  const needle = (q.q ?? '').trim().toLowerCase()
  const rows = getDb().transactions
    .filter((t) => (!q.type || t.type === q.type) && (!q.category || t.category === q.category) && (!q.from || t.date >= q.from) && (!q.to || t.date <= q.to)
      && (!needle || t.counterparty.toLowerCase().includes(needle) || t.note.toLowerCase().includes(needle)))
    .toSorted((a, b) => b.date.localeCompare(a.date) || b.id - a.id)
  res.json(rows)
})

financeRouter.post('/transactions', requirePermission('finance.create'), (req, res) => {
  const r = shape(req.body ?? {})
  if ('error' in r) return res.status(400).json({ error: r.error })
  const db = getDb()
  const staff = req.staff!
  const t: Transaction = { ...r.data, id: nextId(db.transactions), createdBy: { id: staff.id, name: staff.name || staff.username }, createdAt: new Date().toISOString() }
  db.transactions.push(t)
  save()
  res.json(t)
})

financeRouter.put('/transactions/:id', requirePermission('finance.edit'), (req, res) => {
  const t = getDb().transactions.find((x) => x.id === Number(req.params.id))
  if (!t) return res.status(404).json({ error: 'not_found' })
  const r = shape(req.body ?? {})
  if ('error' in r) return res.status(400).json({ error: r.error })
  const { supplierId: _drop, ...rest } = t
  Object.assign(t, rest, r.data)
  if (r.data.category !== 'supplier_payment') delete t.supplierId
  save()
  res.json(t)
})

financeRouter.delete('/transactions/:id', requirePermission('finance.delete'), (req, res) => {
  const db = getDb()
  db.transactions = db.transactions.filter((x) => x.id !== Number(req.params.id))
  save()
  res.json({ ok: true })
})

financeRouter.get('/payables', requirePermission('finance.view'), (_req, res) => res.json(payables()))

reportsRouter.get('/', requirePermission('reports.view'), (req, res) => {
  const now = new Date()
  const first = new Date(now.getFullYear(), now.getMonth(), 1)
  const fmt = (d: Date) => new Date(d.getTime() - d.getTimezoneOffset() * 60_000).toISOString().slice(0, 10)
  const from = typeof req.query.from === 'string' && DATE_RE.test(req.query.from) ? req.query.from : fmt(first)
  const to = typeof req.query.to === 'string' && DATE_RE.test(req.query.to) ? req.query.to : today()
  res.json(report(from, to))
})
