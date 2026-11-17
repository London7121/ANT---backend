import { movementValue, type Movement } from '@ant/shared/warehouse'
import { getDb } from '@/db/store'
import { buildStock, round, stockKey } from './stock'

const sum = (xs: number[]) => xs.reduce((a, b) => a + b, 0)
const money = (n: number) => Math.round(n * 100) / 100
const inRange = (date: string, from: string, to: string) => date >= from && date <= to

/** Yetkazib beruvchilar bo'yicha qarzdorlik: olingan tovar qiymati − to'langan summa. */
export function payables() {
  const { suppliers, movements, transactions } = getDb()
  return suppliers.map((s) => {
    const purchased = sum(movements.filter((m) => m.type === 'RECEIPT' && m.status === 'posted' && m.supplierId === s.id).map(movementValue))
    const paid = sum(transactions.filter((t) => t.type === 'expense' && t.category === 'supplier_payment' && t.supplierId === s.id).map((t) => t.amount))
    return { supplierId: s.id, name: s.name, phone: s.phone, active: s.active, purchased: money(purchased), paid: money(paid), balance: money(purchased - paid) }
  })
}

/** Qoldiq qiymati (oxirgi tannarx bo'yicha): jami, omborlar va kategoriyalar kesimida + kam qolgan tovarlar. */
export function stockValuation() {
  const db = getDb()
  const stock = buildStock(db.movements)
  const byWarehouse = new Map<number, number>()
  const byCategory = new Map<string, number>()
  let totalValue = 0
  let totalQty = 0
  const low: { itemId: number; qty: number; minStock: number }[] = []
  for (const item of db.catalog) {
    let qty = 0
    for (const w of db.warehouses) {
      const q = stock.get(stockKey(item.id, w.id)) ?? 0
      qty += q
      byWarehouse.set(w.id, (byWarehouse.get(w.id) ?? 0) + q * item.cost)
    }
    const value = qty * item.cost
    totalValue += value
    totalQty += qty
    byCategory.set(item.category || '—', (byCategory.get(item.category || '—') ?? 0) + value)
    if (item.active && item.minStock > 0 && qty <= item.minStock) low.push({ itemId: item.id, qty: round(qty), minStock: item.minStock })
  }
  return {
    totalValue: money(totalValue),
    totalQty: round(totalQty),
    low,
    byWarehouse: db.warehouses.map((w) => ({ id: w.id, name: w.name, value: money(byWarehouse.get(w.id) ?? 0) })),
    byCategory: [...byCategory.entries()].map(([category, value]) => ({ category, value: money(value) })).sort((a, b) => b.value - a.value),
  }
}

function eachDay(from: string, to: string): string[] {
  const out: string[] = []
  for (let d = new Date(from); d <= new Date(to) && out.length < 400; d = new Date(d.getTime() + 86_400_000)) out.push(d.toISOString().slice(0, 10))
  return out
}

/** Davr hisoboti: kassa, ombor harakatlari, qoldiq qiymati va qarzdorlik. */
export function report(from: string, to: string) {
  const db = getDb()
  const tx = db.transactions.filter((t) => inRange(t.date, from, to))
  const movements = db.movements.filter((m) => m.status === 'posted' && inRange(m.date, from, to))
  const byCategory = (type: 'income' | 'expense') => {
    const out: Record<string, number> = {}
    for (const t of tx.filter((x) => x.type === type)) out[t.category] = money((out[t.category] ?? 0) + t.amount)
    return out
  }
  const income = money(sum(tx.filter((t) => t.type === 'income').map((t) => t.amount)))
  const expense = money(sum(tx.filter((t) => t.type === 'expense').map((t) => t.amount)))
  const value = (type: Movement['type']) => money(sum(movements.filter((m) => m.type === type).map(movementValue)))
  const counts: Record<string, number> = {}
  for (const m of movements) counts[m.type] = (counts[m.type] ?? 0) + 1
  const stock = stockValuation()
  return {
    period: { from, to },
    finance: {
      income, expense, net: money(income - expense),
      incomeByCategory: byCategory('income'), expenseByCategory: byCategory('expense'),
      byDay: eachDay(from, to).map((date) => ({
        date,
        income: money(sum(tx.filter((t) => t.date === date && t.type === 'income').map((t) => t.amount))),
        expense: money(sum(tx.filter((t) => t.date === date && t.type === 'expense').map((t) => t.amount))),
      })),
    },
    movements: { counts, receiptsValue: value('RECEIPT'), issuesValue: value('ISSUE'), writeOffValue: value('WRITE_OFF') },
    stock: { totalValue: stock.totalValue, totalQty: stock.totalQty, lowStock: stock.low.length, byWarehouse: stock.byWarehouse, byCategory: stock.byCategory },
    payables: { total: money(sum(payables().map((p) => Math.max(p.balance, 0)))) },
  }
}
