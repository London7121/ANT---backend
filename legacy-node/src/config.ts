import path from 'node:path'

const backendRoot = path.resolve(import.meta.dirname, '..')
const dataDir = process.env.DATA_DIR ? path.resolve(process.env.DATA_DIR) : path.join(backendRoot, 'data')

/** Barcha muhit sozlamalari bir joyda. */
export const config = {
  port: Number(process.env.PORT ?? 3001),
  dataDir,
  dbFile: path.join(dataDir, 'db.json'),
  uploadDir: path.join(dataDir, 'uploads'),
  /** Production'da shu papkadagi build qilingan sayt ham beriladi. */
  frontendDist: path.resolve(backendRoot, '../frontend/dist'),
  adminUser: process.env.ADMIN_USER ?? 'admin',
  adminPassword: process.env.ADMIN_PASSWORD,
  /** Faqat testlar uchun: DISABLE_RATE_LIMIT=1 */
  rateLimitEnabled: process.env.DISABLE_RATE_LIMIT !== '1',
} as const
