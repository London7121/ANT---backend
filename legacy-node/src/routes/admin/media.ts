import { Router } from 'express'
import { getDb } from '@/db/store'
import { deleteMedia, processUpload, type ProcessOptions } from '@/media/processor'
import { requirePermission } from '@/middleware/auth'
import { upload } from '@/media/upload'

export const mediaRouter = Router()

const clamp = (value: string | undefined, fallback: number, min: number, max: number) => Math.min(Math.max(parseInt(value ?? '') || fallback, min), max)
const FORMATS = ['webp', 'jpeg', 'png', 'original'] as const

mediaRouter.get('/', requirePermission('media.view'), (_req, res) => res.json(getDb().media))

mediaRouter.post('/', requirePermission('media.create'), upload.single('file'), async (req, res, next) => {
  try {
    if (!req.file) return res.status(400).json({ error: 'file_required_or_type_not_allowed' })
    const body = req.body as Record<string, string | undefined>
    const options: ProcessOptions = {
      maxWidth: clamp(body.maxWidth, 2000, 64, 5000),
      quality: clamp(body.quality, 82, 40, 100),
      format: FORMATS.find((f) => f === body.format) ?? 'webp',
    }
    res.json(await processUpload(req.file, options))
  } catch (error) {
    next(error)
  }
})

mediaRouter.delete('/:id', requirePermission('media.delete'), (req, res) => (deleteMedia(Number(req.params.id)) ? res.json({ ok: true }) : res.status(404).json({ error: 'not_found' })))
