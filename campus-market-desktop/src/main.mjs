import { app, BrowserWindow, Menu, dialog, session } from 'electron'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { verifyWeb } from './web-artifacts.mjs'
import { startLocalServer } from './local-server.mjs'
import { DESKTOP_ORIGIN, WINDOW_PREFERENCES, isAppURL, isAllowedRequest, allowPermission } from './window-policy.mjs'

const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
app.setName('校园集市')
app.setPath('userData', path.join(app.getPath('appData'), 'campus-market-desktop'))
let window = null
let local = null
let quitting = false

function createWindow() {
  window = new BrowserWindow({ title: '校园集市', width: 1100, height: 800,
    minWidth: 720, minHeight: 560, show: false, backgroundColor: '#f5f7fa',
    webPreferences: { ...WINDOW_PREFERENCES } })
  window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }))
  window.webContents.on('will-navigate', (event, url) => { if (!isAppURL(url)) event.preventDefault() })
  window.webContents.on('will-frame-navigate', details => {
    if (!details.isMainFrame || !isAppURL(details.url)) details.preventDefault()
  })
  window.webContents.on('will-attach-webview', event => event.preventDefault())
  window.once('ready-to-show', () => window?.show())
  window.on('closed', () => { window = null })
  window.loadURL(DESKTOP_ORIGIN + '/#/').catch(error => {
    dialog.showErrorBox('无法打开校园集市', error.message)
    app.quit()
  })
}
function setupMenu() {
  const template = []
  if (process.platform === 'darwin') template.push({ label: '校园集市', submenu: [
    { role: 'about', label: '关于校园集市' }, { type: 'separator' },
    { role: 'hide', label: '隐藏校园集市' }, { role: 'hideOthers', label: '隐藏其他应用' },
    { role: 'unhide', label: '显示全部' }, { type: 'separator' }, { role: 'quit', label: '退出校园集市' }
  ] })
  template.push({ label: '编辑', submenu: [{ role: 'undo', label: '撤销' }, { role: 'redo', label: '重做' },
    { type: 'separator' }, { role: 'cut', label: '剪切' }, { role: 'copy', label: '复制' },
    { role: 'paste', label: '粘贴' }, { role: 'selectAll', label: '全选' }] })
  template.push({ label: '视图', submenu: [{ role: 'reload', label: '重新加载' },
    { role: 'resetZoom', label: '恢复缩放' }, { role: 'zoomIn', label: '放大' }, { role: 'zoomOut', label: '缩小' },
    ...(!app.isPackaged ? [{ role: 'toggleDevTools', label: '开发者工具' }] : [])] })
  template.push({ label: '窗口', submenu: [{ role: 'minimize', label: '最小化' }, { role: 'close', label: '关闭窗口' }] })
  Menu.setApplicationMenu(Menu.buildFromTemplate(template))
}

if (!app.requestSingleInstanceLock()) app.quit()
else {
  app.on('second-instance', () => {
    if (!window && local) createWindow()
    if (window) { if (window.isMinimized()) window.restore(); window.focus() }
  })
  app.whenReady().then(async () => {
    await verifyWeb(path.join(project, 'web'))
    local = await startLocalServer({ root: path.join(project, 'web') })
    const browserSession = session.fromPartition(WINDOW_PREFERENCES.partition)
    browserSession.setPermissionRequestHandler((contents, permission, callback, details) => {
      callback(allowPermission(permission, details?.requestingUrl || contents.getURL()))
    })
    browserSession.setPermissionCheckHandler((contents, permission, requestingOrigin, details) => {
      return allowPermission(permission, details?.requestingUrl || contents?.getURL() || requestingOrigin)
    })
    browserSession.webRequest.onBeforeRequest((details, callback) => {
      callback({ cancel: !isAllowedRequest(details.url) })
    })
    setupMenu()
    createWindow()
  }).catch(error => {
    dialog.showErrorBox('校园集市启动失败', error.code === 'EADDRINUSE'
      ? '本机端口5188已被其他程序占用。请关闭占用该端口的程序后重新启动。'
      : '未能加载桌面客户端资源：' + error.message)
    app.quit()
  })
  app.on('activate', () => { if (!quitting && !window && local) createWindow() })
  app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit() })
  app.on('before-quit', () => {
    quitting = true
    local?.close().catch(() => {})
    local = null
    // 不阻止Electron正常退出；系统会完成窗口和会话的关闭。
  })
}
