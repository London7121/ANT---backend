import type { Permission, Role } from '@ant/shared/roles'
import type { Content } from '@ant/shared/types'
import type { CatalogItem, Movement, Supplier, Transaction, Warehouse } from '@ant/shared/warehouse'

export interface MediaItem {
  id: number
  type: 'image' | 'video'
  name: string
  url: string
  thumb?: string
  width?: number
  height?: number
  size: number
  createdAt: string
}

export interface Message {
  id: number
  name: string
  phone: string
  message: string
  read: boolean
  createdAt: string
}

/** Admin panel xodimi */
export interface Staff {
  id: number
  username: string
  name: string
  role: Role
  /** Rol ruxsatlariga qo'shimcha berilgan / olib tashlangan ruxsatlar */
  grant: Permission[]
  revoke: Permission[]
  active: boolean
  hash: string
  createdAt: string
}

/** Landing foydalanuvchisi (ro'yxatdan o'tgan mijoz) */
export interface SiteUser {
  id: number
  username: string
  hash: string
  createdAt: string
  blocked: boolean
  favorites: number[]
}

/** db.json tuzilmasi: ochiq kontent + foydalanuvchilar + xodimlar. */
export interface DB extends Content {
  media: MediaItem[]
  messages: Message[]
  users: SiteUser[]
  staff: Staff[]
  // ---- Ombor va buxgalteriya ----
  warehouses: Warehouse[]
  catalog: CatalogItem[]
  suppliers: Supplier[]
  movements: Movement[]
  transactions: Transaction[]
  /** Hujjat raqamlari uchun hisoblagichlar (tur bo'yicha) */
  counters: Record<string, number>
  /** Token imzolash uchun maxfiy kalit */
  secret: string
}

export const COLLECTIONS = ['banners', 'products', 'posts', 'news', 'reviews'] as const
export type Collection = (typeof COLLECTIONS)[number]
export type CollectionItem = { id: number } & Record<string, unknown>
