import type { NextFunction, Request, Response } from 'express'
import { config } from '@/config'

/** Oddiy xotirada ishlovchi cheklov: windowMs ichida IP boshiga max ta so'rov. */
export function rateLimit(max: number, windowMs: number) {
  const hits = new Map<string, number[]>()
  return (req: Request, res: Response, next: NextFunction) => {
    if (!config.rateLimitEnabled) return next()
    const key = req.ip ?? 'unknown'
    const now = Date.now()
    const recent = (hits.get(key) ?? []).filter((t) => now - t < windowMs)
    if (recent.length >= max) return res.status(429).json({ error: 'too_many_requests' })
    recent.push(now)
    hits.set(key, recent)
    next()
  }
}
