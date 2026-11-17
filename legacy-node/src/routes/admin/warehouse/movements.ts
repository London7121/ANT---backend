import { Router, type Response } from 'express'
import { getDb } from '@/db/store'
import { requirePermission } from '@/middleware/auth'
import { CancelInput, InventoryInput } from '@/schemas'
import { cancelMovement, MovementError, postMovement } from '@/services/movements'
import { buildStock, qtyOf, round } from '@/services/stock'

export const movementsRouter = Router()
export const inventoryRouter = Router()

const fail = (res: Response, e: unknown) => {
  if (e instanceof MovementError) return res.status(e.status).json({ error: e.code, detail: e.detail })
  throw e
}

/** Jurnal: filtrlar + sahifalash. Eng yangisi birinchi. */
movementsRouter.get('/', requirePermission('movements.view'), (req, res) => {
  const q = req.query as Record<string, string | undefined>
  const itemId = Number(q.itemId) || 0
  const warehouseId = Number(q.warehouseId) || 0
  const needle = (q.q ?? '').trim().toLowerCase()
  const rows = getDb().movements
    .filter((m) => (!q.type || m.type === q.type)
      && (!q.status || m.status === q.status)
      && (!warehouseId || m.warehouseId === warehouseId || m.toWarehouseId === warehouseId)
      && (!itemId || m.lines.some((l) => l.itemId === itemId))
      && (!q.from || m.date >= q.from) && (!q.to || m.date <= q.to)
      && (!needle || m.number.toLowerCase().includes(needle) || m.counterparty.toLowerCase().includes(needle) || m.note.toLowerCase().includes(needle) || m.createdBy.name.toLowerCase().includes(needle)))
    .toSorted((a, b) => b.id - a.id)
  const limit = Math.min(Number(q.limit) || 50, 500)
  const offset = Number(q.offset) || 0
  res.json({ total: rows.length, rows: rows.slice(offset, offset + limit) })
})

movementsRouter.post('/', requirePermission('movements.create'), (req, res) => {
  try {
    res.json(postMovement(req.body ?? {}, req.staff!))
  } catch (e) {
    fail(res, e)
  }
})

/** Harakatni bekor qilish (jiddiy amal: alohida ruxsat — movements.delete). */
movementsRouter.post('/:id/cancel', requirePermission('movements.delete'), (req, res) => {
  const { reason } = CancelInput.parse(req.body ?? {})
  try {
    res.json(cancelMovement(Number(req.params.id), reason, req.staff!))
  } catch (e) {
    fail(res, e)
  }
})

/** Inventarizatsiya: sanalgan qoldiq tizim qoldig'idan farq qilsa, tuzatish hujjati (ADJUSTMENT) yaratiladi. */
inventoryRouter.post('/apply', requirePermission('inventory.create'), (req, res) => {
  const db = getDb()
  const { warehouseId, counts, note } = InventoryInput.parse(req.body ?? {})
  const stock = buildStock(db.movements)
  const lines = counts
    .map((c) => ({ itemId: c.itemId, qty: round(c.counted - qtyOf(stock, c.itemId, warehouseId)), price: db.catalog.find((i) => i.id === c.itemId)?.cost ?? 0 }))
    .filter((l) => l.qty !== 0)
  if (lines.length === 0) return res.status(400).json({ error: 'no_differences' })
  try {
    res.json(postMovement({ type: 'ADJUSTMENT', warehouseId, lines, reason: 'inventory', note }, req.staff!))
  } catch (e) {
    fail(res, e)
  }
})
