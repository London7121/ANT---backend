import type { NextFunction, Request, Response } from 'express'
import { ZodError } from 'zod'

export const apiNotFound = (_req: Request, res: Response) => res.status(404).json({ error: 'not_found' })

/** Sxema xabari `kod_ko'rinishida` bo'lsa shu kod qaytadi, aks holda umumiy `invalid`. */
const codeOf = (e: ZodError) => {
  const message = e.issues[0]?.message ?? ''
  return /^[a-z][a-z_]*$/.test(message) ? message : 'invalid'
}

export function errorHandler(err: Error, _req: Request, res: Response, _next: NextFunction) {
  if (err instanceof ZodError) return res.status(400).json({ error: codeOf(err), issues: err.issues.map((i) => ({ path: i.path.join('.'), message: i.message })) })
  console.error(err)
  res.status(500).json({ error: 'server_error', message: err.message })
}
