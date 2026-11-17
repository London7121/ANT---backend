import { Router } from 'express'
import { can } from '@ant/shared/roles'
import { movementValue } from '@ant/shared/warehouse'
import { getDb } from '@/db/store'
import { payables, stockValuation } from '@/services/finance'

export const statsRouter = Router()

const DAYS = 14
const dayKey = (iso: string) => iso.slice(0, 10)

/** Oxirgi N kun uchun kunlik hisoblagich. */
function series(dates: (string | undefined)[], days: string[]): number[] {
  const counts = new Map(days.map((d) => [d, 0]))
  for (const iso of dates) {
    const key = iso ? dayKey(iso) : ''
    if (counts.has(key)) counts.set(key, (counts.get(key) ?? 0) + 1)
  }
  return days.map((d) => counts.get(d) ?? 0)
}

statsRouter.get('/', (req, res) => {
  const db = getDb()
  const days = Array.from({ length: DAYS }, (_, i) => new Date(Date.now() - (DAYS - 1 - i) * 86_400_000).toISOString().slice(0, 10))
  const visibleReviews = db.reviews.filter((r) => r.visible !== false)
  const distribution = [1, 2, 3, 4, 5].map((n) => visibleReviews.filter((r) => r.rating === n).length)
  const average = visibleReviews.length ? visibleReviews.reduce((s, r) => s + r.rating, 0) / visibleReviews.length : 0

  const favoriteCounts = new Map<number, number>()
  for (const u of db.users) for (const id of u.favorites) favoriteCounts.set(id, (favoriteCounts.get(id) ?? 0) + 1)
  const topFavorites = [...favoriteCounts.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 5)
    .flatMap(([id, count]) => {
      const p = db.products.find((x) => x.id === id)
      return p ? [{ id, name: p.name, count }] : []
    })

  const staff = req.staff!
  const today = new Date().toISOString().slice(0, 10)
  const month = today.slice(0, 7)
  const valuation = can(staff, 'stock.view') ? stockValuation() : null
  const warehouse = valuation && {
    items: db.catalog.filter((c) => c.active).length,
    warehouses: db.warehouses.filter((w) => w.active).length,
    movementsToday: db.movements.filter((m) => m.status === 'posted' && m.date === today).length,
    lowStock: valuation.low.slice(0, 6).map((l) => ({ ...l, name: db.catalog.find((c) => c.id === l.itemId)?.name ?? '', unit: db.catalog.find((c) => c.id === l.itemId)?.unit ?? 'pcs' })),
    lowStockCount: valuation.low.length,
    stockValue: can(staff, 'reports.view') ? valuation.totalValue : null,
    recent: can(staff, 'movements.view') ? db.movements.toSorted((a, b) => b.id - a.id).slice(0, 6).map((m) => ({ id: m.id, number: m.number, type: m.type, status: m.status, date: m.date, createdBy: m.createdBy, value: movementValue(m) })) : [],
  }
  const finance = can(staff, 'finance.view') ? {
    incomeMonth: db.transactions.filter((t) => t.type === 'income' && t.date.startsWith(month)).reduce((s, t) => s + t.amount, 0),
    expenseMonth: db.transactions.filter((t) => t.type === 'expense' && t.date.startsWith(month)).reduce((s, t) => s + t.amount, 0),
    payablesTotal: payables().reduce((s, p) => s + Math.max(p.balance, 0), 0),
  } : null

  res.json({
    warehouse,
    finance,
    totals: {
      products: db.products.length,
      productsHidden: db.products.filter((p) => p.visible === false).length,
      posts: db.posts.length,
      news: db.news.length,
      reviews: db.reviews.length,
      reviewsHidden: db.reviews.length - visibleReviews.length,
      users: db.users.length,
      usersBlocked: db.users.filter((u) => u.blocked).length,
      messages: db.messages.length,
      messagesUnread: db.messages.filter((m) => !m.read).length,
      media: db.media.length,
      mediaBytes: db.media.reduce((s, m) => s + m.size, 0),
      favorites: db.users.reduce((s, u) => s + u.favorites.length, 0),
    },
    rating: { average: Math.round(average * 10) / 10, count: visibleReviews.length, distribution },
    series: {
      days,
      messages: series(db.messages.map((m) => m.createdAt), days),
      users: series(db.users.map((u) => u.createdAt), days),
      reviews: series(db.reviews.map((r) => r.createdAt), days),
    },
    topFavorites,
    recent: {
      messages: db.messages.slice(0, 5),
      reviews: db.reviews.toSorted((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? '')).slice(0, 5),
      users: db.users.toSorted((a, b) => b.createdAt.localeCompare(a.createdAt)).slice(0, 5).map((u) => ({ id: u.id, username: u.username, createdAt: u.createdAt })),
    },
  })
})
