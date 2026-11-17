import type { Movement, MovementLine } from '@ant/shared/warehouse'

/** Qoldiq kaliti: tovar + ombor */
export const stockKey = (itemId: number, warehouseId: number) => `${itemId}:${warehouseId}`

type Effect = { itemId: number; warehouseId: number; delta: number }

/** Harakatning har bir tovar/ombor bo'yicha qoldiqqa ta'siri. */
export function effectsOf(m: Pick<Movement, 'type' | 'warehouseId' | 'toWarehouseId' | 'lines'>): Effect[] {
  const out: Effect[] = []
  for (const l of m.lines) {
    const base = { itemId: l.itemId }
    if (m.type === 'RECEIPT') out.push({ ...base, warehouseId: m.warehouseId, delta: l.qty })
    else if (m.type === 'ADJUSTMENT') out.push({ ...base, warehouseId: m.warehouseId, delta: l.qty })
    else if (m.type === 'TRANSFER') out.push({ ...base, warehouseId: m.warehouseId, delta: -l.qty }, { ...base, warehouseId: m.toWarehouseId!, delta: l.qty })
    else out.push({ ...base, warehouseId: m.warehouseId, delta: -l.qty }) // ISSUE, WRITE_OFF
  }
  return out
}

/** Tasdiqlangan (bekor qilinmagan) harakatlardan qoldiqlar xaritasi. Qoldiq saqlanmaydi — har doim hujjatlardan hisoblanadi. */
export function buildStock(movements: Movement[]): Map<string, number> {
  const map = new Map<string, number>()
  for (const m of movements) {
    if (m.status !== 'posted') continue
    for (const e of effectsOf(m)) map.set(stockKey(e.itemId, e.warehouseId), round((map.get(stockKey(e.itemId, e.warehouseId)) ?? 0) + e.delta))
  }
  return map
}

export const round = (n: number) => Math.round(n * 1000) / 1000

/** Yangi harakat qo'llanilgandan keyin manfiy qoldiq paydo bo'lsa — birinchi muammoli kalitni qaytaradi. */
export function firstNegative(stock: Map<string, number>, effects: Effect[], sign: 1 | -1 = 1): Effect | null {
  const next = new Map(stock)
  for (const e of effects) next.set(stockKey(e.itemId, e.warehouseId), round((next.get(stockKey(e.itemId, e.warehouseId)) ?? 0) + sign * e.delta))
  return effects.find((e) => (next.get(stockKey(e.itemId, e.warehouseId)) ?? 0) < 0) ?? null
}

export const qtyOf = (stock: Map<string, number>, itemId: number, warehouseId: number) => stock.get(stockKey(itemId, warehouseId)) ?? 0

export type { MovementLine }
