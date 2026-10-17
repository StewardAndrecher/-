import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { packager } from '@electron/packager'
import { verifyWeb } from '../src/web-artifacts.mjs'

const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
try {
  await verifyWeb(path.join(project, 'web'))
  const outputs = await packager({ dir: project, out: path.join(project, 'dist'), name: 'CampusMarket',
    appBundleId: 'com.campusmarket.desktop', appCopyright: 'Campus Market learning project',
    platform: process.platform, arch: process.arch, asar: true, prune: true, overwrite: false,
    ignore: [/^\/dist(?:\/|$)/, /^\/test(?:\/|$)/, /^\/scripts(?:\/|$)/,
      /^\/\.web-history(?:\/|$)/, /^\/\.web-staging-/, /^\/README\.md$/,
      /^\/package-lock\.json$/] })
  for (const output of outputs) console.log('桌面应用已打包：' + output)
} catch (error) { console.error(error.message); process.exitCode = 1 }
