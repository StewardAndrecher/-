import test from 'node:test'
import assert from 'node:assert/strict'
import { createNetworkMonitor, normalizeNetwork } from '../utils/network-monitor.mjs'

function fixture(overrides = {}) {
  const reads = [], added = [], removed = [], states = []
  const api = {
    getNetworkType: options => reads.push(options),
    onNetworkStatusChange: callback => added.push(callback),
    offNetworkStatusChange: callback => removed.push(callback),
    ...overrides
  }
  const monitor = createNetworkMonitor(() => api, state => states.push(state))
  return { monitor, reads, added, removed, states, last: () => states.at(-1) }
}

test('none/offline is explicit; unknown and malformed values are not treated as offline', () => {
  assert.deepEqual(normalizeNetwork({networkType:'wifi'}),{state:'online',networkType:'wifi'})
  assert.equal(normalizeNetwork({networkType:'none',isConnected:true}).state,'offline')
  assert.equal(normalizeNetwork({networkType:'wifi',isConnected:false}).state,'offline')
  assert.equal(normalizeNetwork({networkType:'unknown',isConnected:true}).state,'online')
  for (const result of [undefined,null,{},'wifi',{networkType:'future-type'},{isConnected:'false'}]) assert.equal(normalizeNetwork(result).state,'unknown')
})
test('multiple consumers share one listener and last release removes the exact callback once', () => {
  const f=fixture(),first=f.monitor.acquire(),second=f.monitor.acquire()
  assert.equal(f.added.length,1);assert.equal(f.reads.length,1)
  first();first();assert.equal(f.removed.length,0)
  second();assert.deepEqual(f.removed,[f.added[0]])
  const count=f.reads.length;f.monitor.refresh();assert.equal(f.reads.length,count)
})
test('fresh events beat old queries; latest refresh wins over out-of-order snapshots', () => {
  const f=fixture(),stop=f.monitor.acquire()
  f.added[0]({isConnected:false,networkType:'none'})
  f.reads[0].success({networkType:'wifi'});assert.equal(f.last().state,'offline')
  f.monitor.refresh();f.monitor.refresh();f.reads[2].success({networkType:'5g'})
  f.reads[1].fail();assert.equal(f.last().networkType,'5g')
  f.added[0]({isConnected:false});f.reads[2].success({networkType:'wifi'});assert.equal(f.last().state,'offline');stop()
})
test('released/restarted generations reject late snapshots and stale listeners', () => {
  const f=fixture(),stop=f.monitor.acquire(),old=f.added[0]
  stop();const count=f.states.length;old({networkType:'wifi'});f.reads[0].fail();assert.equal(f.states.length,count)
  const again=f.monitor.acquire();old({networkType:'none'});f.reads[0].success({networkType:'wifi'});assert.equal(f.last().state,'unknown')
  f.reads[1].success({networkType:'ethernet'});assert.equal(f.last().networkType,'ethernet');again()
})
test('recovery persists until acknowledged, never triggers business transport or automatic retry', () => {
  const f=fixture(),stop=f.monitor.acquire()
  f.reads[0].success({networkType:'none'});f.added[0]({isConnected:true,networkType:'wifi'});assert.equal(f.last().recovered,true)
  f.added[0]({isConnected:true,networkType:'5g'});assert.equal(f.last().recovered,true)
  const count=f.reads.length;f.monitor.acknowledgeRecovery();assert.equal(f.last().recovered,false);assert.equal(f.reads.length,count)
  assert.equal(Object.isFrozen(f.last()),true)
  f.added[0]({isConnected:false});assert.equal(f.last().recovered,false);stop()
})
test('missing APIs and thrown reads/registration/removal degrade without breaking consumers', () => {
  for (const overrides of [
    {getNetworkType:undefined,onNetworkStatusChange:undefined,offNetworkStatusChange:undefined},
    {getNetworkType:()=>{throw new Error('device unavailable')}},
    {onNetworkStatusChange:()=>{throw new Error('unsupported')}},
    {offNetworkStatusChange:()=>{throw new Error('cleanup unavailable')}}
  ]) {const f=fixture(overrides);const stop=f.monitor.acquire();f.monitor.refresh();stop();assert.equal(f.last().state,'unknown')}
  let snapshot;const broken=createNetworkMonitor(()=>{throw new Error('no uni')},s=>snapshot=s);const stop=broken.acquire();assert.equal(snapshot.state,'unknown');stop()
})
test('listener is not installed when matching off API is unavailable', () => {
  const f=fixture({offNetworkStatusChange:undefined}),stop=f.monitor.acquire()
  assert.equal(f.added.length,0);f.reads[0].success({networkType:'wifi'});assert.equal(f.last().state,'online');stop()
})
