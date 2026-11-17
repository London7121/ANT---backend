// Admin parolini tiklash (server to'xtatilgan holda):
//   npm run admin:reset -- <parol (kamida 6 belgi)> [login]
// Login berilmasa — birinchi SUPER_ADMIN'ning paroli yangilanadi va hisob faollashtiriladi.
import fs from 'node:fs'
import { config } from '@/config'
import { hashPassword } from '@/db/password'
import type { DB } from '@/db/types'

const [password, username] = process.argv.slice(2)
if (!password || password.length < 6) {
  console.error('Foydalanish: npm run admin:reset -- <parol (kamida 6 belgi)> [login]')
  process.exit(1)
}
if (!fs.existsSync(config.dbFile)) {
  console.error('db.json topilmadi — avval serverni bir marta ishga tushiring.')
  process.exit(1)
}

const db = JSON.parse(fs.readFileSync(config.dbFile, 'utf8')) as DB
const staff = username ? db.staff?.find((s) => s.username === username) : db.staff?.find((s) => s.role === 'SUPER_ADMIN')
if (!staff) {
  console.error(username ? `"${username}" nomli xodim topilmadi.` : 'SUPER_ADMIN topilmadi.')
  process.exit(1)
}
staff.hash = hashPassword(password)
staff.active = true
fs.writeFileSync(config.dbFile, JSON.stringify(db, null, 2))
console.log(`✅ "${staff.username}" (${staff.role}) — parol yangilandi. Endi serverni ishga tushiring.`)
