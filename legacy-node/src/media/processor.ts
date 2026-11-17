import { randomBytes } from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import sharp from 'sharp'
import { config } from '@/config'
import type { MediaItem } from '@/db/types'
import { getDb, nextId, save } from '@/db/store'
import { VIDEO_TYPES } from './upload'

export interface ProcessOptions {
  maxWidth: number
  quality: number
  format: 'webp' | 'jpeg' | 'png' | 'original'
}

const THUMB_WIDTH = 480
const uid = () => randomBytes(6).toString('hex')
const publicUrl = (file: string) => `/uploads/${file}`
const slug = (original: string) => path.parse(original).name.replace(/[^\w-]+/g, '_').slice(0, 40) || 'file'

/** Fayl o'zgartirilmasdan saqlanadi (video, SVG, GIF). */
function storeAsIs(file: Express.Multer.File, ext: string): { name: string; size: number } {
  const name = `${slug(file.originalname)}-${uid()}${ext}`
  fs.renameSync(file.path, path.join(config.uploadDir, name))
  return { name, size: file.size }
}

/** Rasm: burish (EXIF), kenglikni cheklash, formatga o'tkazish + kichik preview. */
async function processImage(file: Express.Multer.File, opts: ProcessOptions) {
  const fmt = opts.format === 'original' ? (file.mimetype === 'image/png' ? 'png' : file.mimetype === 'image/jpeg' ? 'jpeg' : 'webp') : opts.format
  const name = `${slug(file.originalname)}-${uid()}.${fmt === 'jpeg' ? 'jpg' : fmt}`
  const target = path.join(config.uploadDir, name)

  let pipeline = sharp(file.path, { failOn: 'error' }).rotate().resize({ width: opts.maxWidth, withoutEnlargement: true })
  pipeline = fmt === 'webp' ? pipeline.webp({ quality: opts.quality }) : fmt === 'jpeg' ? pipeline.jpeg({ quality: opts.quality, mozjpeg: true }) : pipeline.png({ compressionLevel: 9 })
  const info = await pipeline.toFile(target)

  const thumb = `thumb-${name.replace(/\.\w+$/, '')}.webp`
  await sharp(target).resize({ width: THUMB_WIDTH, withoutEnlargement: true }).webp({ quality: 70 }).toFile(path.join(config.uploadDir, thumb))
  fs.rmSync(file.path, { force: true })

  return { name, thumb, width: info.width, height: info.height, size: info.size }
}

/** Yuklangan faylni qayta ishlab, media kutubxonasiga qo'shadi. */
export async function processUpload(file: Express.Multer.File, opts: ProcessOptions): Promise<MediaItem> {
  const db = getDb()
  const base = { id: nextId(db.media), name: file.originalname, createdAt: new Date().toISOString() }
  let item: MediaItem

  if (VIDEO_TYPES.has(file.mimetype)) {
    const ext = file.mimetype === 'video/webm' ? '.webm' : file.mimetype === 'video/quicktime' ? '.mov' : '.mp4'
    const { name, size } = storeAsIs(file, ext)
    item = { ...base, type: 'video', url: publicUrl(name), size }
  } else if (file.mimetype === 'image/svg+xml' || file.mimetype === 'image/gif') {
    const { name, size } = storeAsIs(file, file.mimetype === 'image/gif' ? '.gif' : '.svg')
    item = { ...base, type: 'image', url: publicUrl(name), size }
  } else {
    const r = await processImage(file, opts)
    item = { ...base, type: 'image', url: publicUrl(r.name), thumb: publicUrl(r.thumb), width: r.width, height: r.height, size: r.size }
  }

  db.media.unshift(item)
  save()
  return item
}

/** Faylni diskdan va kutubxonadan o'chiradi. */
export function deleteMedia(id: number): boolean {
  const db = getDb()
  const item = db.media.find((m) => m.id === id)
  if (!item) return false
  for (const url of [item.url, item.thumb]) if (url) fs.rmSync(path.join(config.uploadDir, path.basename(url)), { force: true })
  db.media = db.media.filter((m) => m.id !== id)
  save()
  return true
}
