import { Router, type NextFunction, type Request, type Response } from 'express'
import type { Permission } from '@ant/shared/roles'
import { COLLECTIONS, type Collection, type CollectionItem } from '@/db/types'
import { requirePermission } from '@/middleware/auth'
import { getDb, nextId, save } from '@/db/store'
import { COLLECTION_SCHEMAS, ReorderInput } from '@/schemas'

/** banners / products / posts / news / reviews uchun umumiy CRUD + tartiblash. */
export const collectionsRouter = Router()

const isCollection = (name: string): name is Collection => (COLLECTIONS as readonly string[]).includes(name)
const listOf = (name: Collection) => getDb()[name] as unknown as CollectionItem[]

/** Noma'lum to'plam → 404. Ruxsat amalga qarab: yaratish / tahrirlash / o'chirish. */
const guard = (action: 'create' | 'edit' | 'delete') => (req: Request, res: Response, next: NextFunction) => {
  const name = String(req.params.col)
  if (!isCollection(name)) return res.status(404).json({ error: 'not_found' })
  requirePermission(`${name}.${action}` as Permission)(req, res, next)
}

collectionsRouter.post('/:col/reorder', guard('edit'), (req, res) => {
  const name = req.params.col as Collection
  const { ids } = ReorderInput.parse(req.body ?? {})
  const items = listOf(name)
  const ordered = ids.map((id) => items.find((x) => x.id === id)).filter((x): x is CollectionItem => Boolean(x))
  const rest = items.filter((x) => !ids.includes(x.id))
  ;(getDb() as unknown as Record<Collection, CollectionItem[]>)[name] = [...ordered, ...rest]
  save()
  res.json({ ok: true })
})

collectionsRouter.post('/:col', guard('create'), (req, res) => {
  const items = listOf(req.params.col as Collection)
  const item = { ...COLLECTION_SCHEMAS[req.params.col as Collection].parse(req.body ?? {}), id: nextId(items) } as CollectionItem
  items.push(item)
  save()
  res.json(item)
})

collectionsRouter.put('/:col/:id', guard('edit'), (req, res) => {
  const items = listOf(req.params.col as Collection)
  const index = items.findIndex((x) => x.id === Number(req.params.id))
  if (index < 0) return res.status(404).json({ error: 'not_found' })
  items[index] = { ...COLLECTION_SCHEMAS[req.params.col as Collection].parse(req.body ?? {}), id: items[index].id }
  save()
  res.json(items[index])
})

collectionsRouter.delete('/:col/:id', guard('delete'), (req, res) => {
  const name = req.params.col as Collection
  ;(getDb() as unknown as Record<Collection, CollectionItem[]>)[name] = listOf(name).filter((x) => x.id !== Number(req.params.id))
  save()
  res.json({ ok: true })
})
