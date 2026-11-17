import { createApp } from '@/app'
import { config } from '@/config'
import { flushOnExit, initDb } from '@/db/store'

initDb()
flushOnExit()
createApp().listen(config.port, () => console.log(`ANT API → http://localhost:${config.port}`))
