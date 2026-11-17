import fs from 'node:fs'
import path from 'node:path'
import express from 'express'
import { config } from '@/config'
import { docsRouter } from '@/docs/swagger'
import { apiNotFound, errorHandler } from '@/middleware/errors'
import { adminRouter } from '@/routes/admin'
import { authRouter } from '@/routes/auth'
import { meRouter } from '@/routes/me'
import { publicRouter } from '@/routes/public'

export function createApp() {
  const app = express()
  app.set('trust proxy', 1)
  app.disable('x-powered-by')
  app.use(express.json({ limit: '2mb' }))

  // Yuklangan fayllar: SVG ichidagi skriptlar ishlamasligi uchun qat'iy sarlavhalar
  app.use(
    '/uploads',
    (_req, res, next) => {
      res.setHeader('X-Content-Type-Options', 'nosniff')
      res.setHeader('Content-Security-Policy', "default-src 'none'; style-src 'unsafe-inline'; img-src 'self'; media-src 'self'")
      res.setHeader('Cache-Control', 'public, max-age=31536000, immutable')
      next()
    },
    express.static(config.uploadDir, { dotfiles: 'deny' }),
  )

  if (process.env.DOCS !== '0') app.use('/api/docs', docsRouter)
  app.use('/api/admin', adminRouter)
  app.use('/api/auth', authRouter)
  app.use('/api/me', meRouter)
  app.use('/api', publicRouter)
  app.use('/api', apiNotFound)

  // Production: build qilingan frontend'ni ham beramiz (SPA fallback bilan)
  if (fs.existsSync(config.frontendDist)) {
    app.use(express.static(config.frontendDist, { index: false, maxAge: '1h' }))
    app.get(/^(?!\/(api|uploads)\/).*/, (_req, res) => res.sendFile(path.join(config.frontendDist, 'index.html')))
  }

  app.use(errorHandler)
  return app
}
