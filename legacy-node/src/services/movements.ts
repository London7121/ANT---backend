import { MOVEMENT_TYPES, type Movement, type MovementLine, type MovementType } from '@ant/shared/warehouse'
import { getDb, nextId, save } from '@/db/store'
import type { Staff } from '@/db/types'
import { buildStock, effectsOf, firstNegative, round } from './stock'

const PREFIX: Record<MovementType, string> = { RECEIPT: 'KR', ISSUE: 'CH', TRANSFER: 'KO', WRITE_OFF: 'HC', ADJUSTMENT: 'IN' }
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/

export class MovementError extends Error {
  code: string
  status: number
  detail?: unknown
  constructor(code: string, status = 400, detail?: unknown) {
    super(code)
    this.code = code
    this.status = status
    this.detail = detail
  }
}

const str = (v: unknown, max: number) => (typeof v === 'string' ? v.trim().slice(0, max) : '')
const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : Number.NaN)

/** Harakatni tekshiradi va omborga o'tkazadi (qoldiqlar o'zgaradi). Xatolikda MovementError. */
export function postMovement(input: Record<string, unknown>, staff: Staff): Movement {
  const db = getDb()
  const type = input.type as MovementType
  if (!MOVEMENT_TYPES.includes(type)) throw new MovementError('invalid_type')

  const warehouse = db.warehouses.find((w) => w.id === input.warehouseId && w.active)
  if (!warehouse) throw new MovementError('warehouse_not_found')
  let toWarehouseId: number | undefined
  if (type === 'TRANSFER') {
    const to = db.warehouses.find((w) => w.id === input.toWarehouseId && w.active)
    if (!to || to.id === warehouse.id) throw new MovementError('invalid_destination')
    toWarehouseId = to.id
  }
  let supplierId: number | undefined
  if (type === 'RECEIPT' && input.supplierId) {
    if (!db.suppliers.some((s) => s.id === input.supplierId)) throw new MovementError('supplier_not_found')
    supplierId = Number(input.supplierId)
  }

  const rawLines = Array.isArray(input.lines) ? (input.lines as Record<string, unknown>[]) : []
  if (rawLines.length === 0 || rawLines.length > 200) throw new MovementError('invalid_lines')
  const lines: MovementLine[] = rawLines.map((l) => {
    const item = db.catalog.find((c) => c.id === l.itemId && c.active)
    if (!item) throw new MovementError('item_not_found', 400, l.itemId)
    const qty = round(num(l.qty))
    if (!Number.isFinite(qty) || qty === 0 || (type !== 'ADJUSTMENT' && qty < 0)) throw new MovementError('invalid_qty', 400, item.id)
    const price = type === 'RECEIPT' ? num(l.price) : Number.isFinite(num(l.price)) ? num(l.price) : item.cost
    if (!Number.isFinite(price) || price < 0) throw new MovementError('invalid_price', 400, item.id)
    return { itemId: item.id, qty, price }
  })
  if (new Set(lines.map((l) => l.itemId)).size !== lines.length) throw new MovementError('duplicate_items')

  const draft = { type, warehouseId: warehouse.id, toWarehouseId, lines }
  const bad = firstNegative(buildStock(db.movements), effectsOf(draft))
  if (bad) throw new MovementError('insufficient_stock', 409, { itemId: bad.itemId, warehouseId: bad.warehouseId })

  db.counters[type] = (db.counters[type] ?? 0) + 1
  const movement: Movement = {
    id: nextId(db.movements),
    number: `${PREFIX[type]}-${String(db.counters[type]).padStart(5, '0')}`,
    type,
    date: typeof input.date === 'string' && DATE_RE.test(input.date) ? input.date : new Date().toISOString().slice(0, 10),
    warehouseId: warehouse.id,
    ...(toWarehouseId ? { toWarehouseId } : {}),
    ...(supplierId ? { supplierId } : {}),
    counterparty: str(input.counterparty, 160),
    reason: str(input.reason, 40),
    lines,
    note: str(input.note, 500),
    status: 'posted',
    createdBy: { id: staff.id, name: staff.name || staff.username },
    createdAt: new Date().toISOString(),
  }
  db.movements.push(movement)
  // Kirimda tannarx oxirgi sotib olish narxiga yangilanadi
  if (type === 'RECEIPT') for (const l of lines) if (l.price > 0) db.catalog.find((c) => c.id === l.itemId)!.cost = l.price
  save()
  return movement
}

/** Harakatni bekor qiladi (qoldiq manfiy bo'lib qolmasa). */
export function cancelMovement(id: number, reason: string, staff: Staff): Movement {
  const db = getDb()
  const m = db.movements.find((x) => x.id === id)
  if (!m) throw new MovementError('not_found', 404)
  if (m.status !== 'posted') throw new MovementError('already_cancelled', 409)
  const bad = firstNegative(buildStock(db.movements), effectsOf(m), -1)
  if (bad) throw new MovementError('cancel_would_go_negative', 409, { itemId: bad.itemId, warehouseId: bad.warehouseId })
  m.status = 'cancelled'
  m.cancelledBy = { id: staff.id, name: staff.name || staff.username }
  m.cancelledAt = new Date().toISOString()
  m.cancelReason = reason.slice(0, 300)
  save()
  return m
}
