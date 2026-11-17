import type { SiteUser, Staff } from '@/db/types'

declare global {
  namespace Express {
    interface Request {
      /** requireStaff o'rnatadi */
      staff?: Staff
      /** requireUser o'rnatadi */
      siteUser?: SiteUser
    }
  }
}

export {}
