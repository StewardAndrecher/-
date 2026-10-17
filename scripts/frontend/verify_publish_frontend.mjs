import fs from 'node:fs'
import path from 'node:path'
import assert from 'node:assert/strict'
import {createRequire} from 'node:module'
const require=createRequire(import.meta.url)
const {parse,compileScript,compileTemplate}=require(process.env.UNI_COMPILER_HOME+'/node_modules/@vue/compiler-sfc')
const root=path.resolve('campus-market-client')
for(const name of ['pages/index/index.vue','pages/product/detail.vue','components/ProductCard.vue','pages/user/login.vue','pages/user/register.vue','components/ImagePicker.vue','pages/product/mine.vue']) {
  const filename=path.join(root,name),{descriptor,errors}=parse(fs.readFileSync(filename,'utf8'),{filename})
  assert.deepEqual(errors,[])
  const script=compileScript(descriptor,{id:name})
  assert.deepEqual(compileTemplate({source:descriptor.template.content,filename,id:name,compilerOptions:{bindingMetadata:script.bindings}}).errors,[])
}
const session=await import('../.frontend-work/esm/utils/session.js')
const api=await import('../.frontend-work/esm/api/product.js')
const user={id:1,username:'student_01',nickname:'小明'}
let requests=[]
globalThis.uni={request:options=>requests.push(options)}
session.clearSession()
await assert.rejects(api.createProduct({title:'测试商品'}),e=>e.statusCode===401)
assert.equal(requests.length,0)
session.saveSession({accessToken:'A'.repeat(43),tokenType:'Bearer',expiresInSeconds:7200,user})
let operation=api.createProduct({title:'测试商品',priceInCents:900})
assert.equal(requests.at(-1).header.Authorization,'Bearer '+'A'.repeat(43))
requests.at(-1).success({statusCode:401,data:{message:'登录已失效'}})
await assert.rejects(operation,e=>e.statusCode===401)
assert.equal(session.currentUser.value,null)
operation=api.getProduct(1)
assert.equal(requests.at(-1).header.Authorization,undefined)
requests.at(-1).success({statusCode:200,data:{id:1}});await operation

console.log('PASS: seven Vue SFCs; legacy createProduct auth header and 401 clearing; guest product read without Authorization.')
