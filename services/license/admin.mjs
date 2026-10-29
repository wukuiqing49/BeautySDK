import {readFile,writeFile,rename,unlink,open} from 'node:fs/promises';
import {generateKeyPairSync,createPublicKey} from 'node:crypto';
import {envelope,hash,signingKey,validateGrant,validateRegistry,DAY} from './protocol.mjs';

// 私钥路径/登记表位于发行方控制的目录，不放进CMS、APK或仓库。所有签发输出禁止覆盖。
const [command,...args]=process.argv.slice(2);
if(command==='keygen'){
 const [privatePath,publicPath]=args;if(!privatePath||!publicPath)throw Error('keygen PRIVATE_PEM PUBLIC_DER_BASE64');
 const {privateKey,publicKey}=generateKeyPairSync('rsa',{modulusLength:3072});
 await writeFile(privatePath,privateKey.export({type:'pkcs8',format:'pem'}),{flag:'wx',mode:0o600});
 await writeFile(publicPath,publicKey.export({type:'spki',format:'der'}).toString('base64'),{flag:'wx'});
}else if(command==='issue'||command==='revoke'||command==='resume'||command==='renew'){
 const [registryPath,...rest]=args;if(!registryPath)throw Error('Registry path required');
 const lock=await open(registryPath+'.lock','wx');
 try{
  let registry;try{registry=JSON.parse(await readFile(registryPath,'utf8'));}catch(e){if(e.code!=='ENOENT')throw e;registry={version:1,licenses:[]};}
  validateRegistry(registry);
  if(command==='issue'){
   const [claimsPath,privatePath,keyId,outputPath]=rest;if(!outputPath)throw Error('issue REGISTRY CLAIMS_JSON PRIVATE_PEM KEY_ID OUTPUT_LICENSE');
   const claims=validateGrant(JSON.parse(await readFile(claimsPath,'utf8'))),key=signingKey(await readFile(privatePath));
   if(registry.licenses.some(x=>x.licenseId===claims.licenseId))throw Error('License ID exists; use a new ID for renewal/reissue');
   const signed=envelope('grant',claims,key,keyId);
   registry.licenses.push({licenseId:claims.licenseId,grantSha256:hash(signed),status:'active',expiresAt:claims.expiresAt,features:claims.features,leaseMs:7*DAY,refreshMs:DAY});
   validateRegistry(registry);await writeFile(outputPath,signed,{flag:'wx'});
  }else{
   const record=registry.licenses.find(x=>x.licenseId===rest[0]);if(!record)throw Error('Unknown license');if(command==='renew'){const expiry=Number(rest[1]);if(!Number.isSafeInteger(expiry)||expiry<=Date.now())throw Error('Renewal requires a future epoch-millisecond expiry');record.expiresAt=expiry;}record.status=command==='revoke'?'revoked':'active';
  }
  const temp=registryPath+`.${process.pid}.tmp`;
  try{await writeFile(temp,JSON.stringify(registry,null,2)+'\n',{flag:'wx',mode:0o600});await rename(temp,registryPath);}finally{await unlink(temp).catch(()=>{});}
 }finally{await lock.close();await unlink(registryPath+'.lock');}
}else if(command==='config'){
 const [privatePath,keyId,endpoint,outputPath]=args;if(!outputPath)throw Error('config PRIVATE_PEM KEY_ID HTTPS_ENDPOINT OUTPUT_JSON');
 const url=new URL(endpoint);if(url.protocol!=='https:'||url.username||url.password||url.search||url.hash)throw Error('Fixed HTTPS URL required');
 if(!/^[A-Za-z0-9_-]{1,64}$/.test(keyId))throw Error('Invalid key id');
 const key=signingKey(await readFile(privatePath));
 await writeFile(outputPath,JSON.stringify({endpoint,keys:{[keyId]:createPublicKey(key).export({type:'spki',format:'der'}).toString('base64')}},null,2)+'\n',{flag:'wx'});
}else throw Error('Commands: keygen, issue, revoke, resume, renew, config. See README.md');
