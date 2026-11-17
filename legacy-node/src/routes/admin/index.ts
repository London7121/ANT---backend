import { Router } from 'express'
import { requirePermission, requireStaff } from '@/middleware/auth'
import { accountRouter, loginRouter } from './auth'
import { collectionsRouter } from './collections'
import { contentRouter } from './content'
import { mediaRouter } from './media'
import { messagesRouter } from './messages'
import { staffRouter } from './staff'
import { statsRouter } from './stats'
import { usersRouter } from './users'
import { financeRouter, reportsRouter } from './finance'
import { catalogRouter } from './warehouse/catalog'
import { inventoryRouter, movementsRouter } from './warehouse/movements'
import { suppliersRouter, warehousesRouter } from './warehouse/simple'

/**
 * /api/admin — kirishdan tashqari hammasi token talab qiladi,
 * har bir bo'lim esa rol ruxsatiga bog'liq (shared/roles.ts).
 */
export const adminRouter = Router()
adminRouter.use(loginRouter)
adminRouter.use(requireStaff)
adminRouter.use(accountRouter)
adminRouter.use(contentRouter)
adminRouter.use('/stats', requirePermission('dashboard.view'), statsRouter)
adminRouter.use('/media', mediaRouter)
adminRouter.use('/messages', messagesRouter)
adminRouter.use('/users', usersRouter)
adminRouter.use('/staff', staffRouter)
adminRouter.use('/warehouses', warehousesRouter)
adminRouter.use('/suppliers', suppliersRouter)
adminRouter.use('/catalog', catalogRouter)
adminRouter.use('/movements', movementsRouter)
adminRouter.use('/inventory', inventoryRouter)
adminRouter.use('/finance', financeRouter)
adminRouter.use('/reports', reportsRouter)
adminRouter.use(collectionsRouter) // oxirida: /:col/... shablonlari
