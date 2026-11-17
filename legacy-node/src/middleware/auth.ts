import { createHmac, timingSafeEqual } from 'node:crypto'
import type { NextFunction, Request, Response } from 'express'
import { can, type Permission } from '@ant/shared/roles'
import { getDb } from '@/db/store'

type TokenKind = 'staff' | 'user'
interface TokenPayload { k: TokenKind; id: number; exp: number }

const DAY_MS = 24 * 60 * 60 * 1000
/** Xodim tokeni qisqa (12 soat), foydalanuvchi tokeni uzoq: bir marta kirib, doimiy yurishi uchun. */
const TTL: Record<TokenKind, number> = { staff: DAY_MS / 2, user: 365 * DAY_MS }

const sign = (body: string) => createHmac('sha256', getDb().secret).update(body).digest('base64url')

export function signToken(kind: TokenKind, id: number): string {
  const payload: TokenPayload = { k: kind, id, exp: Date.now() + TTL[kind] }
  const body = Buffer.from(JSON.stringify(payload)).toString('base64url')
  return `${body}.${sign(body)}`
}

function readToken(req: Request, kind: TokenKind): TokenPayload | null {
  const token = (req.headers.authorization ?? '').replace(/^Bearer /, '')
  const [body, signature] = token.split('.')
  if (!body || !signature) return null
  const a = Buffer.from(signature)
  const b = Buffer.from(sign(body))
  if (a.length !== b.length || !timingSafeEqual(a, b)) return null
  try {
    const payload = JSON.parse(Buffer.from(body, 'base64url').toString()) as TokenPayload
    return payload.k === kind && payload.exp > Date.now() ? payload : null
  } catch {
    return null
  }
}

/** Admin xodimi: token to'g'ri va hisob faol bo'lishi shart. */
export function requireStaff(req: Request, res: Response, next: NextFunction) {
  const payload = readToken(req, 'staff')
  const staff = payload && getDb().staff.find((s) => s.id === payload.id)
  if (!staff || !staff.active) return res.status(401).json({ error: 'unauthorized' })
  req.staff = staff
  next()
}

/** Landing foydalanuvchisi: bloklangan bo'lsa 403. */
export function requireUser(req: Request, res: Response, next: NextFunction) {
  const payload = readToken(req, 'user')
  const user = payload && getDb().users.find((u) => u.id === payload.id)
  if (!user) return res.status(401).json({ error: 'unauthorized' })
  if (user.blocked) return res.status(403).json({ error: 'blocked' })
  req.siteUser = user
  next()
}

/** Xodimda shu ruxsat bo'lishi shart (rol + shaxsiy o'zgarishlar bo'yicha). */
export function requirePermission(permission: Permission) {
  return (req: Request, res: Response, next: NextFunction) => {
    if (!can(req.staff, permission)) return res.status(403).json({ error: 'forbidden', permission })
    next()
  }
}
