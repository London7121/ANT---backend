import { Router } from 'express'
import type { z } from 'zod'
import type { CatalogItem } from '@ant/shared/warehouse'
import { getDb, nextId, save } from '@/db/store'
import { requirePermission } from '@/middleware/auth'
import { CatalogInput as CatalogSchema } from '@/schemas'
import { buildStock, effectsOf, stockKey } from '@/services/stock'

export const catalogRouter = Router()

/** Sxema (CatalogInput) tozalaydi; SKU/shtrix kod takrorlanishi bu yerda tekshiriladi (bazaga bog'liq). */
function shape(body: unknown, selfId?: number): { data: z.output<typeof CatalogSchema> } | { error: string } {
  const data = CatalogSchema.parse(body ?? {})
  const others = getDb().catalog.filter((c) => c.id !== selfId)
  if (others.some((c) => c.sku.toLowerCase() === data.sku.toLowerCase())) return { error: 'sku_taken' }
  if (others.some((c) => c.barcodes.some((x) => data.barcodes.includes(x)))) return { error: 'barcode_taken' }
  return { data }
}

/** Katalog + har bir ombordagi qoldiq (hujjatlardan hisoblanadi). */
catalogRouter.get('/', requirePermission('catalog.view'), (_req, res) => {
  const db = getDb()
  const stock = buildStock(db.movements)
  res.json(
    db.catalog.map((item) => {
      const byWarehouse: Record<number, number> = {}
      let total = 0
      for (const w of db.warehouses) {
        const q = stock.get(stockKey(item.id, w.id)) ?? 0
        if (q !== 0) byWarehouse[w.id] = q
        total += q
      }
      return { ...item, stock: byWarehouse, stockTotal: Math.round(total * 1000) / 1000 }
    }),
  )
})

/** Shtrix kod yoki SKU bo'yicha tez qidirish (skaner uchun). */
catalogRouter.get('/lookup', requirePermission('catalog.view'), (req, res) => {
  const code = (typeof req.query.code === 'string' ? req.query.code.trim().slice(0, 40) : '').toLowerCase()
  const item = code && getDb().catalog.find((c) => c.sku.toLowerCase() === code || c.barcodes.some((b) => b.toLowerCase() === code))
  return item ? res.json(item) : res.status(404).json({ error: 'not_found' })
})

/** Tovar kartochkasi: shu tovar bo'yicha oxirgi harakatlar. */
catalogRouter.get('/:id/history', requirePermission('catalog.view'), (req, res) => {
  const id = Number(req.params.id)
  const rows = getDb().movements
    .filter((m) => m.lines.some((l) => l.itemId === id))
    .toSorted((a, b) => b.id - a.id)
    .slice(0, 50)
    .map((m) => {
      const line = m.lines.find((l) => l.itemId === id)!
      return { id: m.id, number: m.number, type: m.type, date: m.date, status: m.status, warehouseId: m.warehouseId, toWarehouseId: m.toWarehouseId, qty: line.qty, price: line.price, createdBy: m.createdBy, effects: effectsOf({ ...m, lines: [line] }) }
    })
  res.json(rows)
})

catalogRouter.post('/', requirePermission('catalog.create'), (req, res) => {
  const r = shape(req.body ?? {})
  if ('error' in r) return res.status(409).json({ error: r.error })
  const db = getDb()
  const item: CatalogItem = { ...r.data, id: nextId(db.catalog), createdAt: new Date().toISOString() }
  db.catalog.push(item)
  save()
  res.json(item)
})

catalogRouter.put('/:id', requirePermission('catalog.edit'), (req, res) => {
  const item = getDb().catalog.find((c) => c.id === Number(req.params.id))
  if (!item) return res.status(404).json({ error: 'not_found' })
  const r = shape(req.body ?? {}, item.id)
  if ('error' in r) return res.status(409).json({ error: r.error })
  Object.assign(item, r.data)
  save()
  res.json(item)
})

catalogRouter.delete('/:id', requirePermission('catalog.delete'), (req, res) => {
  const db = getDb()
  const id = Number(req.params.id)
  if (db.movements.some((m) => m.lines.some((l) => l.itemId === id))) return res.status(409).json({ error: 'in_use' })
  db.catalog = db.catalog.filter((c) => c.id !== id)
  save()
  res.json({ ok: true })
})
