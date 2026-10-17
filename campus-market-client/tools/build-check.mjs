import fs from 'node:fs/promises'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { fileInventory, sameInventory } from './build-files.mjs'

const scopes = text => new Set(text.match(/\bdata-v-[a-zA-Z0-9_-]+\b/g) || [])
export function checkScopedFiles(template, style, logic, name) {
  const templateScopes = scopes(template)
  for (const scope of scopes(style)) {
    if (!templateScopes.has(scope)) throw new Error(`${name}：WXSS 的 ${scope} 在 WXML 中不存在，模板与样式可能混用了不同构建`)
  }
  for (const match of logic.matchAll(/__scopeId["']?\s*[,=:]\s*["'](data-v-[a-zA-Z0-9_-]+)["']/g)) {
    if (!templateScopes.has(match[1])) throw new Error(`${name}：JS 的 ${match[1]} 与 WXML 不一致`)
  }
  return templateScopes.size
}

export async function checkPlatform(output, platform) {
  const inventory = await fileInventory(output, { metadata: true })
  if (!Object.keys(inventory).length) throw new Error('构建输出为空')
  if (platform === 'h5') {
    const html = await fs.readFile(path.join(output, 'index.html'), 'utf8')
    const assets = [...html.matchAll(/(?:src|href)=["']([^"']+)["']/g)].map(m => m[1])
    for (const asset of assets) {
      if (/^(?:https?:|data:|\/\/|#)/.test(asset)) continue
      const name = asset.split(/[?#]/)[0].replace(/^\//, '').replace(/^\.\//, '')
      if (!inventory[name]) throw new Error(`网页引用的资源缺失：${asset}`)
    }
    if (!assets.some(asset => /\.js(?:[?#]|$)/.test(asset))) throw new Error('网页没有入口脚本')
    return { files: Object.keys(inventory).length, pages: null, scopedFiles: 0 }
  }
  if (platform === 'app') {
    const manifest = JSON.parse(await fs.readFile(path.join(output, 'manifest.json'), 'utf8'))
    if (!/^__UNI__[A-Za-z0-9]+$/.test(manifest.id) || !manifest.name?.trim() ||
        !manifest['@platforms']?.includes('android') || !manifest['@platforms']?.includes('iPhone')) {
      throw new Error('App资源的DCloud AppID、名称或目标平台无效')
    }
    for (const name of ['app-service.js', 'app-config-service.js', 'app-config.js', 'app.css',
        '__uniappview.html', 'uni-app-view.umd.js']) {
      if (!inventory[name]) throw new Error(`App资源文件缺失：${name}`)
    }
    if (manifest.launch_path !== '__uniappview.html') throw new Error('App启动资源路径无效')
    // 编译器把路由配置写成单行JSON声明；只解析JSON，不执行App业务代码。
    const service = await fs.readFile(path.join(output, 'app-config-service.js'), 'utf8')
    const configMatch = /\b(?:const|let|var)\s+__uniConfig\s*=\s*(\{[^\n]*\});/.exec(service)
    const routesMatch = /\b(?:const|let|var)\s+__uniRoutes\s*=\s*(\[[^\n]*\])(?=\.map\(|;)/.exec(service)
    if (!configMatch || !routesMatch) throw new Error('无法读取App编译路由，请检查编译器输出格式')
    const config = JSON.parse(configMatch[1]), routes = JSON.parse(routesMatch[1])
    const pages = routes.map(route => route.path)
    if (!pages.length || new Set(pages).size !== pages.length || !pages.includes(config.entryPagePath)) {
      throw new Error('App路由为空、重复或缺少启动页')
    }
    for (const route of routes) {
      if (!/^[\w/-]+$/.test(route.path) || route.path.split('/').includes('..')) throw new Error('App页面路径无效')
      if (route.meta?.isNVue) throw new Error('当前校验仅支持本项目Vue页面，nvue需单独适配')
      if (!inventory[`${route.path}.css`]) throw new Error(`App页面样式缺失：${route.path}.css`)
    }
    return { files: Object.keys(inventory).length, pages: pages.length, scopedFiles: 0, installable: false }
  }
  if (platform !== 'mp-weixin') throw new Error('不支持的构建平台')
  const app = JSON.parse(await fs.readFile(path.join(output, 'app.json'), 'utf8'))
  const project = JSON.parse(await fs.readFile(path.join(output, 'project.config.json'), 'utf8'))
  if (project.compileType !== 'miniprogram' || !/^wx[0-9a-f]{16}$/.test(project.appid)) throw new Error('小程序项目类型或AppID无效')
  const pages = [...(app.pages || []), ...(app.subPackages || app.subpackages || []).flatMap(group => group.pages.map(page => `${group.root}/${page}`))]
  if (!pages.length || new Set(pages).size !== pages.length) throw new Error('小程序页面目录为空或重复')
  for (const page of pages) {
    if (!/^[\w/-]+$/.test(page) || page.split('/').includes('..')) throw new Error('小程序页面路径无效')
    for (const extension of ['js', 'json', 'wxml', 'wxss']) if (!inventory[`${page}.${extension}`]) throw new Error(`小程序页面文件缺失：${page}.${extension}`)
  }
  let scopedFiles = 0
  for (const name of Object.keys(inventory).filter(name => name.endsWith('.wxml'))) {
    const base = name.slice(0, -5)
    for (const extension of ['js', 'json', 'wxss']) if (!inventory[`${base}.${extension}`]) throw new Error(`组件文件缺失：${base}.${extension}`)
    const [template, style, logic] = await Promise.all(['wxml', 'wxss', 'js'].map(extension => fs.readFile(path.join(output, `${base}.${extension}`), 'utf8')))
    if (checkScopedFiles(template, style, logic, base)) scopedFiles++
  }
  return { files: Object.keys(inventory).length, pages: pages.length, scopedFiles }
}

export async function verifyBuild(output) {
  const meta = JSON.parse(await fs.readFile(path.join(output, 'build-meta.json'), 'utf8'))
  if (meta.schemaVersion !== 1 || !['h5', 'mp-weixin', 'app'].includes(meta.platform) || !meta.artifacts) throw new Error('构建清单格式无效')
  const actual = await fileInventory(output, { metadata: true })
  if (!sameInventory(meta.artifacts, actual)) throw new Error('构建产物与清单不一致，可能被覆盖、遗漏或添加了文件，请重新构建')
  return { platform: meta.platform, ...await checkPlatform(output, meta.platform) }
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  try {
    if (!process.argv[2]) throw new Error('用法：node tools/build-check.mjs <产物目录>')
    console.log('验收通过：', await verifyBuild(path.resolve(process.argv[2])))
  } catch (error) { console.error(error.message); process.exitCode = 1 }
}
