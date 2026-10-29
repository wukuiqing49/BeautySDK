import {createHash,createPrivateKey,createPublicKey,sign,verify} from 'node:crypto';

export const DAY=86400000;
export const FEATURES=['BEAUTY','FILTER','FACE','RESHAPE','MAKEUP','STICKER','BACKGROUND','MOTION','GESTURE'];
export const hash=value=>createHash('sha256').update(value).digest('hex');
const fail=message=>{throw new Error(message);};
const fields=(value,names)=>{if(!value||Array.isArray(value)||typeof value!=='object'||Object.keys(value).sort().join()!==[...names].sort().join())fail('INVALID_FIELDS');};
const integer=value=>Number.isSafeInteger(value)&&value>=0;
export function featureList(value){if(!Array.isArray(value)||value.length>FEATURES.length||new Set(value).size!==value.length||value.some(x=>!FEATURES.includes(x)))fail('INVALID_FEATURES');return value;}
function certificates(value){if(!Array.isArray(value)||!value.length||value.length>8||new Set(value).size!==value.length||value.some(x=>typeof x!=='string'||!/^[0-9a-f]{64}$/.test(x)))fail('INVALID_CERTIFICATES');return value;}
export function validateGrant(c){
 fields(c,['licenseId','packageName','certificates','sdkMajor','features','issuedAt','expiresAt']);
 if(typeof c.licenseId!=='string'||!/^[A-Za-z0-9_-]{1,80}$/.test(c.licenseId)||typeof c.packageName!=='string'||!/^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/.test(c.packageName)||!integer(c.sdkMajor)||!integer(c.issuedAt)||!c.issuedAt||!integer(c.expiresAt)||c.expiresAt&&c.expiresAt<=c.issuedAt)fail('INVALID_GRANT');
 certificates(c.certificates);featureList(c.features);
 if(c.features.some(x=>['RESHAPE','MAKEUP','STICKER'].includes(x))&&!c.features.includes('FACE'))fail('FACE_PERMISSION_REQUIRED');
 return c;
}
export function signingKey(pem){const key=createPrivateKey(pem);if(key.asymmetricKeyType!=='rsa'||key.asymmetricKeyDetails.modulusLength<2048||key.asymmetricKeyDetails.modulusLength>4096)fail('INVALID_SIGNING_KEY');return key;}
export function envelope(kind,claims,key,keyId){
 if(!['grant','lease'].includes(kind)||!/^[A-Za-z0-9_-]{1,64}$/.test(keyId))fail('INVALID_SIGNING_INPUT');
 const payload=JSON.stringify(claims);if(Buffer.byteLength(payload)>8192)fail('PAYLOAD_TOO_LARGE');
 return JSON.stringify({version:1,keyId,payload,signature:sign('RSA-SHA256',Buffer.from(`BeautySDK/license/${kind}/v1\n${payload}`),key).toString('hex')});
}
export function verifyEnvelope(kind,raw,keys){
 if(typeof raw!=='string'||Buffer.byteLength(raw)>32768)fail('ENVELOPE_SIZE');
 const e=JSON.parse(raw);fields(e,['version','keyId','payload','signature']);
 if(e.version!==1||typeof e.payload!=='string'||Buffer.byteLength(e.payload)>8192||typeof e.signature!=='string'||!/^[0-9a-f]{512,1024}$/.test(e.signature))fail('INVALID_ENVELOPE');
 const key=keys.get(e.keyId);if(!key||key.asymmetricKeyType!=='rsa'||key.asymmetricKeyDetails.modulusLength/4!==e.signature.length)fail('UNKNOWN_KEY');
 if(!verify('RSA-SHA256',Buffer.from(`BeautySDK/license/${kind}/v1\n${e.payload}`),key,Buffer.from(e.signature,'hex')))fail('INVALID_SIGNATURE');
 return JSON.parse(e.payload);
}
export function validateRegistry(data){
 fields(data,['version','licenses']);if(data.version!==1||!Array.isArray(data.licenses)||data.licenses.length>10000)fail('INVALID_REGISTRY');
 const ids=new Set();for(const r of data.licenses){
  fields(r,['licenseId','grantSha256','status','expiresAt','features','leaseMs','refreshMs']);
  if(typeof r.licenseId!=='string'||!/^[A-Za-z0-9_-]{1,80}$/.test(r.licenseId)||ids.has(r.licenseId)||typeof r.grantSha256!=='string'||!/^[0-9a-f]{64}$/.test(r.grantSha256)||!['active','revoked'].includes(r.status)||!integer(r.expiresAt)||!integer(r.leaseMs)||r.leaseMs<60000||r.leaseMs>7*DAY||!integer(r.refreshMs)||r.refreshMs<60000||r.refreshMs>DAY||r.refreshMs>r.leaseMs)fail('INVALID_LICENSE_RECORD');
  ids.add(r.licenseId);featureList(r.features);
 }return data;
}
export function exchange(request,registry,key,keyId,keys,now=Date.now()){
 fields(request,['version','grant','nonce','packageName','certificates','sdkMajor']);
 if(request.version!==1||typeof request.nonce!=='string'||!/^[0-9a-f]{64}$/.test(request.nonce))fail('INVALID_REQUEST');
 const grant=validateGrant(verifyEnvelope('grant',request.grant,keys));
 certificates(request.certificates);
 if(request.packageName!==grant.packageName||request.sdkMajor!==grant.sdkMajor||!request.certificates.every(x=>grant.certificates.includes(x)))fail('APP_MISMATCH');
 if(now<grant.issuedAt)fail('GRANT_NOT_YET_VALID');
 const digest=hash(request.grant),record=validateRegistry(registry).licenses.find(x=>x.licenseId===grant.licenseId&&x.grantSha256===digest);
 const limit=Math.min(grant.expiresAt||Number.MAX_SAFE_INTEGER,record?.expiresAt||Number.MAX_SAFE_INTEGER);
 const reason=!record?'NOT_REGISTERED':record.status==='revoked'?'REVOKED':now>=limit?'EXPIRED':'OK';
 const active=reason==='OK',expiresAt=active?Math.min(now+record.leaseMs,limit):now+DAY;
 return envelope('lease',{licenseId:grant.licenseId,grantSha256:digest,nonce:request.nonce,status:active?'ACTIVE':'DENIED',reason,
  issuedAt:now,expiresAt,refreshAt:Math.min(now+(record?.refreshMs??DAY),expiresAt),
  features:active?grant.features.filter(x=>record.features.includes(x)):[]},key,keyId);
}
export function publicKeys(key,keyId,extra={}){
 const keys=new Map(Object.entries(extra).map(([id,der])=>[id,createPublicKey({key:Buffer.from(der,'base64'),format:'der',type:'spki'})]));
 keys.set(keyId,createPublicKey(key));return keys;
}
