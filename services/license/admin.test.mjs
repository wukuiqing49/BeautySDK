import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,writeFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {execFileSync} from 'node:child_process';
import {generateKeyPairSync,createPublicKey} from 'node:crypto';
import {verifyEnvelope,hash} from './protocol.mjs';

test('issuer CLI issues, renews, revokes and resumes without overwriting customer grant', async () => {
  const dir=await mkdtemp(join(tmpdir(),'beauty-license-cli-'));
  const cli=fileURLToPath(new URL('./admin.mjs',import.meta.url));
  const run=(...args)=>execFileSync(process.execPath,[cli,...args],{stdio:'pipe'});
  try {
    const privatePath=join(dir,'private.pem'),registry=join(dir,'registry.json'),claimsPath=join(dir,'claims.json'),output=join(dir,'customer.license'),config=join(dir,'public.json');
    const {privateKey}=generateKeyPairSync('rsa',{modulusLength:2048});
    await writeFile(privatePath,privateKey.export({type:'pkcs8',format:'pem'}));
    const claims={licenseId:'test-customer',packageName:'com.test.app',certificates:['a'.repeat(64)],sdkMajor:0,features:['BEAUTY'],issuedAt:Date.now()-1000,expiresAt:0};
    await writeFile(claimsPath,JSON.stringify(claims));
    run('issue',registry,claimsPath,privatePath,'test-only',output);
    const grant=await readFile(output,'utf8');
    assert.deepEqual(verifyEnvelope('grant',grant,new Map([['test-only',createPublicKey(privateKey)]])),claims);
    const read=async()=>JSON.parse(await readFile(registry,'utf8')).licenses[0];
    assert.equal((await read()).grantSha256,hash(grant));
    assert.throws(()=>run('issue',registry,claimsPath,privatePath,'test-only',output));
    run('revoke',registry,claims.licenseId);assert.equal((await read()).status,'revoked');
    run('resume',registry,claims.licenseId);assert.equal((await read()).status,'active');
    const expiry=Date.now()+86400000;
    run('renew',registry,claims.licenseId,String(expiry));assert.equal((await read()).expiresAt,expiry);
    assert.throws(()=>run('renew',registry,claims.licenseId,'1'));
    assert.equal(await readFile(output,'utf8'),grant);
    run('config',privatePath,'test-only','https://license.example.com/v1/lease',config);
    const publicConfig=JSON.parse(await readFile(config,'utf8'));
    assert.equal(publicConfig.keys['test-only'],createPublicKey(privateKey).export({type:'spki',format:'der'}).toString('base64'));
    assert.throws(()=>run('config',privatePath,'test-only','http://example.com',join(dir,'bad.json')));
  } finally { await rm(dir,{recursive:true,force:true}); }
});
