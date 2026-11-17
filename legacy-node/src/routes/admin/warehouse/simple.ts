import { Router } from 'express'
import type { ZodType } from 'zod'
import type { Module } from '@ant/shared/roles'
import type { Supplier, Warehouse } from '@ant/shared/warehouse'
import { getDb, nextId, save } from '@/db/store'
import { requirePermission } from '@/middleware/auth'
import { SupplierInput, WarehouseInput } from '@/schemas'

/** Ombor va yetkazib beruvchi uchun bir xil CRUD (ro'yxat, yaratish, tahrirlash, o'chirish). */
function crud<T extends { id: number; name: string }>(
  key: 'warehouses' | 'suppliers',
  module: Module,
  input: ZodType<Omit<T, 'id'>>,
  inUse: (id: number) => boolean,
) {
  const router = Router()
  const list = () => getDb()[key] as unknown as T[]

  router.get('/', requirePermission(`${module}.view` as never), (_req, res) => res.json(list()))

  router.post('/', requirePermission(`${module}.create` as never), (req, res) => {
    const data = input.parse(req.body ?? {})
    const item = { ...data, id: nextId(list()) } as T
    list().push(item)
    save()
    res.json(item)
  })

  router.put('/:id', requirePermission(`${module}.edit` as never), (req, res) => {
    const items = list()
    const i = items.findIndex((x) => x.id === Number(req.params.id))
    if (i < 0) return res.status(404).json({ error: 'not_found' })
    const data = input.parse(req.body ?? {})
    items[i] = { ...data, id: items[i].id } as T
    save()
    res.json(items[i])
  })

  router.delete('/:id', requirePermission(`${module}.delete` as never), (req, res) => {
    const id = Number(req.params.id)
    if (inUse(id)) return res.status(409).json({ error: 'in_use' })
    ;(getDb() as unknown as Record<string, T[]>)[key] = list().filter((x) => x.id !== id)
    save()
    res.json({ ok: true })
  })

  return router
}

export const warehousesRouter = crud<Warehouse>(
  'warehouses',
  'warehouses',
  WarehouseInput,
  (id) => getDb().movements.some((m) => m.warehouseId === id || m.toWarehouseId === id),
)

export const suppliersRouter = crud<Supplier>(
  'suppliers',
  'suppliers',
  SupplierInput,
  (id) => getDb().movements.some((m) => m.supplierId === id) || getDb().transactions.some((t) => t.supplierId === id),
)
