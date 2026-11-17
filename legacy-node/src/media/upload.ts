import { randomBytes } from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import multer from 'multer'
import { config } from '@/config'

export const IMAGE_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp', 'image/gif', 'image/avif', 'image/svg+xml'])
export const VIDEO_TYPES = new Set(['video/mp4', 'video/webm', 'video/quicktime'])
export const MAX_UPLOAD_BYTES = 300 * 1024 * 1024

const tmpDir = path.join(config.uploadDir, '.tmp')
fs.mkdirSync(tmpDir, { recursive: true })

/** Fayl avval vaqtinchalik papkaga tushadi, so'ng media/processor.ts qayta ishlaydi. */
export const upload = multer({
  storage: multer.diskStorage({ destination: tmpDir, filename: (_req, _file, cb) => cb(null, randomBytes(8).toString('hex')) }),
  limits: { fileSize: MAX_UPLOAD_BYTES },
  fileFilter: (_req, file, cb) => cb(null, IMAGE_TYPES.has(file.mimetype) || VIDEO_TYPES.has(file.mimetype)),
})
