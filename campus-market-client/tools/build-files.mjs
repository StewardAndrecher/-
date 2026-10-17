import fs from 'node:fs/promises'
import path from 'node:path'
import { createHash } from 'node:crypto'

const ignored = new Set(['.git', '.idea', '.hbuilderx', 'node_modules', 'unpackage', '.DS_Store'])
export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex')

export async function fileInventory(root, { source = false, metadata = false } = {}) {
  const files = {}
  async function visit(directory, relative = '') {
    for (const entry of (await fs.readdir(directory, { withFileTypes: true })).sort((a,b) => a.name.localeCompare(b.name))) {
      if ((source && ignored.has(entry.name)) || (metadata && relative === '' && ['build-meta.json', 'project.private.config.json', '.DS_Store'].includes(entry.name))) continue
      const name = relative ? `${relative}/${entry.name}` : entry.name
      if (entry.isSymbolicLink()) throw new Error(`不能把符号链接加入构建快照：${name}`)
      if (entry.isDirectory()) await visit(path.join(directory, entry.name), name)
      else if (entry.isFile()) files[name] = sha256(await fs.readFile(path.join(directory, entry.name)))
    }
  }
  await visit(root)
  return files
}
export function sameInventory(left, right) {
  const keys = Object.keys(left).sort()
  return keys.length === Object.keys(right).length && keys.every(key => left[key] === right[key])
}
export async function copySource(root, destination, inventory) {
  for (const [name, hash] of Object.entries(inventory)) {
    const bytes = await fs.readFile(path.join(root, name))
    if (sha256(bytes) !== hash) throw new Error(`复制期间源文件改变：${name}`)
    const target = path.join(destination, name)
    await fs.mkdir(path.dirname(target), { recursive: true })
    await fs.writeFile(target, bytes)
  }
}
