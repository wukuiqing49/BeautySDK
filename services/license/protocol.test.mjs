import test from 'node:test';
import assert from 'node:assert/strict';
import {generateKeyPairSync} from 'node:crypto';
import {once} from 'node:events';
import {envelope,exchange,hash,publicKeys,verifyEnvelope,validateRegistry,DAY} from './protocol.mjs';
import {createLicenseServer} from './server.mjs';
const {privateKey}=generateKeyPairSync('rsa',{modulusLength:2048}),keyId='test',keys=publicKeys(privateKey,keyId),now=1793232000000;
function fixture(){
 const claims={licenseId:'customer-app',packageName:'com.example.app',certificates:['a'.repeat(64)],sdkMajor:0,features:['BEAUTY','FILTER'],issuedAt:now-1000,expiresAt:0};
 const grant=envelope('grant',claims,privateKey,keyId);
 return {claims,request:{version:1,grant,nonce:'b'.repeat(64),packageName:claims.packageName,certificates:claims.certificates,sdkMajor:0},registry:{version:1,licenses:[{licenseId:claims.licenseId,grantSha256:hash(grant),status:'active',expiresAt:0,features:claims.features,leaseMs:7*DAY,refreshMs:DAY}]}};
}
test('active lease binds grant and nonce with 24h refresh / 7d cap',()=>{const {request,registry}=fixture();const c=verifyEnvelope('lease',exchange(request,registry,privateKey,keyId,keys,now),keys);assert.equal(c.nonce,request.nonce);assert.equal(c.grantSha256,hash(request.grant));assert.equal(c.refreshAt,now+DAY);assert.equal(c.expiresAt,now+7*DAY);assert.deepEqual(c.features,['BEAUTY','FILTER']);});
test('tampered grant and wrong domain rejected',()=>{const {request,registry,claims}=fixture();const e=JSON.parse(request.grant);e.payload=e.payload.replace('com.example.app','com.example.bad');assert.throws(()=>exchange({...request,grant:JSON.stringify(e)},registry,privateKey,keyId,keys,now),/SIGNATURE/);assert.throws(()=>verifyEnvelope('grant',envelope('lease',claims,privateKey,keyId),keys),/SIGNATURE/);});
test('app signer and SDK identity cannot be substituted',()=>{const {request,registry}=fixture();for(const patch of [{packageName:'com.other.app'},{certificates:['c'.repeat(64)]},{sdkMajor:1}])assert.throws(()=>exchange({...request,...patch},registry,privateKey,keyId,keys,now),/MISMATCH/);});
test('revocation and expiry produce signed denial, not transport failure',()=>{const {request,registry}=fixture();registry.licenses[0].status='revoked';let c=verifyEnvelope('lease',exchange(request,registry,privateKey,keyId,keys,now),keys);assert.equal(c.reason,'REVOKED');assert.deepEqual(c.features,[]);registry.licenses[0].status='active';registry.licenses[0].expiresAt=now;c=verifyEnvelope('lease',exchange(request,registry,privateKey,keyId,keys,now),keys);assert.equal(c.reason,'EXPIRED');});
test('lease cannot extend contract expiry and features are intersected',()=>{const {request,registry}=fixture();registry.licenses[0].expiresAt=now+3600000;registry.licenses[0].features=['BEAUTY','GESTURE'];const c=verifyEnvelope('lease',exchange(request,registry,privateKey,keyId,keys,now),keys);assert.equal(c.expiresAt,now+3600000);assert.deepEqual(c.features,['BEAUTY']);});
test('invalid registry budgets and duplicate ids rejected',()=>{const {registry}=fixture();registry.licenses[0].leaseMs=8*DAY;assert.throws(()=>validateRegistry(registry));registry.licenses[0].leaseMs=7*DAY;registry.licenses.push({...registry.licenses[0]});assert.throws(()=>validateRegistry(registry));});
test('unregistered grant returns signed denial',()=>{const {request}=fixture();assert.equal(verifyEnvelope('lease',exchange(request,{version:1,licenses:[]},privateKey,keyId,keys,now),keys).reason,'NOT_REGISTERED');});
test('HTTP endpoint exchanges signed lease; malformed request cannot grant; unavailable registry is transient',async()=>{
 const {request,registry}=fixture();let unavailable=false;
 const server=createLicenseServer({key:privateKey,keyId,keys,readRegistry:async()=>{if(unavailable)throw Error('disk');return registry;}});
 server.listen(0,'127.0.0.1');await once(server,'listening');
 try{const url=`http://127.0.0.1:${server.address().port}/v1/lease`;
  let r=await fetch(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(request)});assert.equal(r.status,200);assert.equal(verifyEnvelope('lease',await r.text(),keys).status,'ACTIVE');
  r=await fetch(url,{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'});assert.equal(r.status,400);
  unavailable=true;r=await fetch(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(request)});assert.equal(r.status,503);
 }finally{server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}
});
