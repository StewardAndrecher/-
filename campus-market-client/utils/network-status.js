import { readonly, shallowRef } from 'vue'
import { createNetworkMonitor } from './network-monitor.mjs'

const current = shallowRef(Object.freeze({ state: 'unknown', networkType: 'unknown', recovered: false }))
const monitor = createNetworkMonitor(() => uni, value => { current.value = value })
export const networkStatus = readonly(current)
export const acquireNetworkStatus = monitor.acquire
export const refreshNetworkStatus = monitor.refresh
export const acknowledgeNetworkRecovery = monitor.acknowledgeRecovery
