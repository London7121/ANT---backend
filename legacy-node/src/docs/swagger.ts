import { createRequire } from 'node:module'
import express, { Router } from 'express'
import { openapi } from './openapi'

const swaggerDist = createRequire(import.meta.url)('swagger-ui-dist') as { getAbsoluteFSPath(): string }

const page = `<!doctype html>
<html lang="uz"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ANT API</title><link rel="stylesheet" href="/api/docs/assets/swagger-ui.css"></head>
<body><div id="ui"></div>
<script src="/api/docs/assets/swagger-ui-bundle.js"></script>
<script src="/api/docs/init.js"></script></body></html>`

const init = `window.ui = SwaggerUIBundle({ url: '/api/docs/openapi.json', dom_id: '#ui', persistAuthorization: true, tryItOutEnabled: true, docExpansion: 'none', filter: true })`

/** /api/docs — Swagger UI (o'z paketidan, CDN siz). Production'da DOCS=0 bilan o'chiriladi. */
export const docsRouter = Router()
docsRouter.get('/openapi.json', (_req, res) => res.json(openapi))
docsRouter.get('/init.js', (_req, res) => res.type('js').send(init))
docsRouter.use('/assets', express.static(swaggerDist.getAbsoluteFSPath(), { index: false }))
docsRouter.get('/', (_req, res) => res.type('html').send(page))
