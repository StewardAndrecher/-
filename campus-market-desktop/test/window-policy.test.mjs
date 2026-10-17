import test from 'node:test'
import assert from 'node:assert/strict'
import { DESKTOP_ORIGIN, DESKTOP_PORT, WINDOW_PREFERENCES, isAppURL, isAllowedRequest, allowPermission } from '../src/window-policy.mjs'
test('fixed application origin supports hash routes and rejects external or credential URLs',()=>{
 assert.equal(DESKTOP_PORT,5188)
 for(const url of [DESKTOP_ORIGIN+'/',DESKTOP_ORIGIN+'/#/pages/product/detail?id=7'])assert.ok(isAppURL(url))
 for(const url of ['https://external.example/','file:///private/file','javascript:alert(1)',DESKTOP_ORIGIN+'/other','http://user@127.0.0.1:5188/','http://127.0.0.1:5189/','invalid'])assert.ok(!isAppURL(url),url)
})
test('network confined to bundled pages and specified backend API paths',()=>{
 for(const url of [DESKTOP_ORIGIN+'/assets/app.js','http://localhost:8080/api/products','http://127.0.0.1:8080/api/images/image'])assert.ok(isAllowedRequest(url))
 for(const url of ['http://localhost:8080/private','http://127.0.0.1:8081/api/products','https://external.example/api/products','http://localhost:8080.evil.test/api/products','http://user:password@localhost:8080/api/products','file:///private/file'])assert.ok(!isAllowedRequest(url),url)
})
test('permissions limited to trusted page clipboard-write and loopback connection',()=>{
 assert.ok(allowPermission('clipboard-sanitized-write',DESKTOP_ORIGIN+'/#/'))
 assert.ok(allowPermission('loopback-network',DESKTOP_ORIGIN+'/'))
 for(const permission of ['clipboard-read','media','geolocation','notifications','fileSystem','local-network'])assert.ok(!allowPermission(permission,DESKTOP_ORIGIN+'/'))
 assert.ok(!allowPermission('clipboard-sanitized-write','https://foreign.example/'))
})
test('renderer has no Node access, isolated context, sandbox and persistent application storage',()=>{
 assert.equal(WINDOW_PREFERENCES.nodeIntegration,false);assert.equal(WINDOW_PREFERENCES.contextIsolation,true)
 assert.equal(WINDOW_PREFERENCES.sandbox,true);assert.equal(WINDOW_PREFERENCES.webSecurity,true)
 assert.equal(WINDOW_PREFERENCES.partition,'persist:campus-market-desktop')
})
