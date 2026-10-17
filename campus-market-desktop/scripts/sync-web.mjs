import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { randomUUID } from 'node:crypto'
import { verifyWeb } from '../src/web-artifacts.mjs'

const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const client = process.env.CAMPUS_CLIENT_DIR || path.resolve(project, '../campus-market-client')
const source = path.join(client, 'unpackage/verified/h5')
const destination = path.join(project, 'web')
const runId = randomUUID()
const staging = path.join(project, '.web-staging-' + runId)
const previous = path.join(project, '.web-history', runId)
let archived = false
try {
  await verifyWeb(source)
  await fs.cp(source, staging, { recursive: true })
  await verifyWeb(staging)
  await fs.mkdir(path.dirname(previous), { recursive: true })
  try { await fs.rename(destination, previous); archived = true }
  catch (error) { if (error.code !== 'ENOENT') throw error }
  try { await fs.rename(staging, destination) }
  catch (error) { if (archived) await fs.rename(previous, destination); throw error }
  console.log('已同步并校验uni-app网页产物：' + destination)
} catch (error) { console.error(error.message); process.exitCode = 1 }
finally { await fs.rm(staging, { recursive: true, force: true }) }
