import fs from 'node:fs/promises'
import path from 'node:path'
import { createHash } from 'node:crypto'

export async function inventory(root) {
  const files = {}
  async function visit(relative = '') {
    for (const entry of await fs.readdir(path.join(root, relative), { withFileTypes: true })) {
      const name = relative ? `${relative}/${entry.name}` : entry.name
      if (entry.isSymbolicLink()) throw new Error('网页产物不能包含符号链接：' + name)
      if (entry.isDirectory()) await visit(name)
      else if (entry.isFile() && name !== 'build-meta.json' && name !== '.DS_Store') {
        files[name] = createHash('sha256').update(await fs.readFile(path.join(root, name))).digest('hex')
      }
    }
  }
  await visit()
  return files
}
export async function verifyWeb(root) {
  const meta = JSON.parse(await fs.readFile(path.join(root, 'build-meta.json'), 'utf8'))
  const actual = await inventory(root)
  if (meta.schemaVersion !== 1 || meta.platform !== 'h5' || !actual['index.html'] ||
      !meta.artifacts || Object.keys(meta.artifacts).length !== Object.keys(actual).length ||
      Object.keys(actual).some(name => actual[name] !== meta.artifacts[name])) {
    throw new Error('网页产物校验失败，请先在前端项目运行tools/build.sh h5')
  }
  return meta
}
