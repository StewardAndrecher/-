import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawn } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { fileInventory, sameInventory, copySource, sha256 } from './build-files.mjs'
import { checkPlatform, verifyBuild } from './build-check.mjs'

const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const compiler = process.env.UNI_COMPILER_HOME || '/Applications/HBuilderX.app/Contents/HBuilderX/plugins/uniapp-cli-vite'
const requested = process.argv[2] || 'all'
const platforms = requested === 'all' ? ['h5', 'mp-weixin', 'app'] : [requested]
const runId = `${new Date().toISOString().replace(/[:.]/g, '-')}-${randomUUID().slice(0, 8)}`
const outputRoot = path.join(project, 'unpackage', 'verified')
const lock = path.join(outputRoot, '.build-lock')
const workspace = path.join(project, 'unpackage', '.verified-build-work', runId)
let locked = false
const cancellation = new AbortController()
let cancellationCode = 0
for (const [signal, code] of [['SIGINT', 130], ['SIGTERM', 143]]) {
  process.once(signal, () => { cancellationCode = code; cancellation.abort() })
}

async function compile(input, output, platform, logPath) {
  const log = await fs.open(logPath, 'wx')
  try {
    await new Promise((resolve, reject) => {
      const child = spawn(process.execPath, [path.join(compiler, 'node_modules/@dcloudio/vite-plugin-uni/bin/uni.js'), 'build', '-p', platform], {
        cwd: input, env: { ...process.env, UNI_INPUT_DIR: input, UNI_OUTPUT_DIR: output },
        stdio: ['ignore', log.fd, log.fd], signal: cancellation.signal
      })
      child.once('error', reject)
      child.once('exit', (code, signal) => code === 0 ? resolve() : reject(new Error(`编译失败：${platform}，退出状态 ${code ?? signal}，日志 ${logPath}`)))
    })
  } finally { await log.close() }
}

try {
  if (platforms.some(platform => !['h5', 'mp-weixin', 'app'].includes(platform))) throw new Error('平台只能是 all、h5、mp-weixin 或 app')
  const compilerPackage = await fs.readFile(path.join(compiler, 'package.json'))
  const compilerInfo = JSON.parse(compilerPackage)
  await fs.access(path.join(compiler, 'node_modules/@dcloudio/vite-plugin-uni/bin/uni.js'))
  await fs.mkdir(outputRoot, { recursive: true })
  try { await fs.mkdir(lock); locked = true }
  catch (error) { if (error.code === 'EEXIST') throw new Error('已有构建正在运行，请等它结束；异常退出留下的锁请先核对进程'); throw error }
  await fs.writeFile(path.join(lock, 'owner.json'), JSON.stringify({ pid: process.pid, runId, startedAt: new Date().toISOString() }, null, 2))
  const source = await fileInventory(project, { source: true })
  const input = path.join(workspace, 'input')
  await copySource(project, input, source)
  // 适配HBuilderX项目：依赖只链接到临时快照，不改动原项目。
  await fs.writeFile(path.join(input, 'package.json'), compilerPackage)
  await fs.writeFile(path.join(input, 'vite.config.js'), 'import { defineConfig } from "vite";\nimport uni from "@dcloudio/vite-plugin-uni";\nexport default defineConfig({ plugins: [uni()] });\n')
  await fs.symlink(path.join(compiler, 'node_modules'), path.join(input, 'node_modules'), 'dir')
  await fs.mkdir(path.join(outputRoot, 'logs'), { recursive: true })
  const results = []
  // 先把所选平台全部编译和校验成功，再替换可供预览的目录。
  for (const platform of platforms) {
    const output = path.join(workspace, platform)
    const logPath = path.join(outputRoot, 'logs', `${runId}-${platform}.log`)
    cancellation.signal.throwIfAborted()
    console.log(`开始构建 ${platform}，日志：${logPath}`)
    await compile(input, output, platform, logPath)
    const validation = await checkPlatform(output, platform)
    const meta = { schemaVersion: 1, platform, runId, builtAt: new Date().toISOString(),
      compiler: { version: compilerInfo.version, packageSha256: sha256(compilerPackage) },
      nodeVersion: process.version, source, artifacts: await fileInventory(output, { metadata: true }), validation }
    await fs.writeFile(path.join(output, 'build-meta.json'), JSON.stringify(meta, null, 2))
    await verifyBuild(output)
    results.push({ platform, output, validation })
  }
  if (!sameInventory(source, await fileInventory(project, { source: true }))) throw new Error('构建期间源码已改变，本次不替换预览产物，请重新构建')
  cancellation.signal.throwIfAborted()
  for (const result of results) {
    const destination = path.join(outputRoot, result.platform)
    const previous = path.join(outputRoot, 'history', `${runId}-${result.platform}`)
    let archived = false
    try {
      await fs.mkdir(path.dirname(previous), { recursive: true })
      try { await fs.rename(destination, previous); archived = true }
      catch (error) { if (error.code !== 'ENOENT') throw error }
      try { await fs.rename(result.output, destination) }
      catch (error) { if (archived) await fs.rename(previous, destination); throw error }
      console.log(`构建并验收通过：${destination}`, result.validation)
    } catch (error) { throw new Error(`产物替换失败：${result.platform}，${error.message}`) }
  }
  if (platforms.includes('app')) console.log('App资源编译完成；不是APK/IPA，运行需HBuilderX基座和手机/模拟器。正式接口未配置时App将阻止业务请求。')
  console.log('完成。微信开发者工具请导入 unpackage/verified/mp-weixin；HBuilderX的dist/dev目录保持独立。')
} catch (error) {
  console.error(cancellation.signal.aborted ? '构建已取消，正在清理临时目录与构建锁' : error.message)
  process.exitCode = cancellationCode || 1
} finally {
  if (locked) {
    await fs.rm(workspace, { recursive: true, force: true })
    await fs.rm(lock, { recursive: true, force: true })
  }
}
