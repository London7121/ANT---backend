import { randomBytes } from 'node:crypto'
import fs from 'node:fs'
import { defaultContent } from '@ant/shared/content'
import { config } from '@/config'
import { hashPassword } from './password'
import type { DB, Staff } from './types'

let db: DB
let timer: NodeJS.Timeout | undefined
let dirty = false

export const getDb = () => db

/** Bazani diskdan o'qiydi; yo'q bo'lsa, standart kontent bilan yaratadi. */
export function initDb(): DB {
  fs.mkdirSync(config.dataDir, { recursive: true })
  fs.mkdirSync(config.uploadDir, { recursive: true })

  if (fs.existsSync(config.dbFile)) {
    db = migrate(JSON.parse(fs.readFileSync(config.dbFile, 'utf8')))
    return db
  }

  const password = config.adminPassword ?? 'admin123'
  if (!config.adminPassword) console.warn('\n⚠️  ADMIN_PASSWORD berilmagan: standart login "admin" / "admin123". Admin paneldan darhol almashtiring!\n')
  db = {
    ...structuredClone(defaultContent),
    media: [],
    messages: [],
    users: [],
    warehouses: [{ id: 1, name: 'Asosiy ombor', address: '', note: '', active: true }],
    catalog: [],
    suppliers: [],
    movements: [],
    transactions: [],
    counters: {},
    staff: [{ id: 1, username: config.adminUser, name: 'Super admin', role: 'SUPER_ADMIN', grant: [], revoke: [], active: true, hash: hashPassword(password), createdAt: new Date().toISOString() }],
    secret: randomBytes(32).toString('hex'),
  }
  save(true)
  return db
}

/** Eski db.json formatlarini yangisiga o'tkazadi va yangi maydonlarni to'ldiradi. */
function migrate(raw: DB & { admin?: { username: string; hash: string; secret: string } }): DB {
  const data = raw
  data.settings = { ...structuredClone(defaultContent.settings), ...data.settings }
  data.translations ??= { uz: {}, ru: {}, en: {} }
  data.media ??= []
  data.messages ??= []
  data.users ??= []
  if (!data.staff && data.admin) {
    // Bitta admin → xodimlar ro'yxati (SUPER_ADMIN)
    const staff: Staff = { id: 1, username: data.admin.username, name: 'Super admin', role: 'SUPER_ADMIN', grant: [], revoke: [], active: true, hash: data.admin.hash, createdAt: new Date().toISOString() }
    data.staff = [staff]
    data.secret = data.admin.secret
  }
  delete data.admin
  data.staff ??= []
  for (const s of data.staff) { s.grant ??= []; s.revoke ??= [] }
  data.warehouses ??= [{ id: 1, name: 'Asosiy ombor', address: '', note: '', active: true }]
  data.catalog ??= []
  data.suppliers ??= []
  data.movements ??= []
  data.transactions ??= []
  data.counters ??= {}
  data.secret ??= randomBytes(32).toString('hex')
  return data
}

/** Atomik yozish (tmp + rename). Oddiy so'rovlarda debounce bilan. */
export function save(immediately = false) {
  dirty = true
  const write = () => {
    const tmp = config.dbFile + '.tmp'
    fs.writeFileSync(tmp, JSON.stringify(db, null, 2))
    fs.renameSync(tmp, config.dbFile)
    dirty = false
  }
  clearTimeout(timer)
  if (immediately) write()
  else timer = setTimeout(write, 150)
}

/** Chiqishda faqat saqlanmagan o'zgarish bo'lsa yozamiz — aks holda eski xotira diskdagi yangi faylni bosib ketadi. */
export function flushOnExit() {
  const flush = () => {
    if (dirty) save(true)
    process.exit(0)
  }
  process.on('SIGINT', flush)
  process.on('SIGTERM', flush)
}

export const nextId = (list: { id: number }[]) => list.reduce((max, x) => Math.max(max, x.id), 0) + 1
