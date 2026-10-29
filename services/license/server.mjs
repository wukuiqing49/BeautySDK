import http from 'node:http';
import {readFile} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';
import {exchange,publicKeys,signingKey,validateRegistry} from './protocol.mjs';

// 仅绑定回环地址。公网TLS、请求速率限制和运维认证由反向代理配置；没有公开管理接口。
export function createLicenseServer({key,keyId,keys=publicKeys(key,keyId),readRegistry}){
 let active=0;const rates=new Map();
 const server=http.createServer(async(req,res)=>{
  res.setHeader('Cache-Control','no-store');res.setHeader('Content-Type','application/json');
  if(req.method!=='POST'||req.url!=='/v1/lease'){res.writeHead(404).end('{"error":"NOT_FOUND"}');return;}
  const now=Date.now(),address=req.socket.remoteAddress??'unknown';
  for(const[ip,r]of rates)if(now-r.start>=60000)rates.delete(ip);
  const rate=rates.get(address)??{start:now,count:0};rate.count++;rates.set(address,rate);
  if(active>=32||rate.count>120||rates.size>4096){res.writeHead(429).end('{"error":"RETRY_LATER"}');req.resume();return;}
  active++;
  try{
   if(!/^application\/json(?:;|$)/i.test(req.headers['content-type']??'')){res.writeHead(415).end('{"error":"CONTENT_TYPE"}');req.resume();return;}
   const chunks=[];let size=0;
   for await(const chunk of req){size+=chunk.length;if(size>49152){res.writeHead(413).end('{"error":"REQUEST_TOO_LARGE"}');return;}chunks.push(chunk);}
   const request=JSON.parse(Buffer.concat(chunks).toString('utf8'));
   let registry;try{registry=validateRegistry(await readRegistry());}catch{res.writeHead(503).end('{"error":"SERVICE_UNAVAILABLE"}');return;}
   const result=exchange(request,registry,key,keyId,keys);
   res.writeHead(200).end(result);
  }catch{if(!res.headersSent)res.writeHead(400).end('{"error":"INVALID_REQUEST"}');}
  finally{active--;}
 });
 server.requestTimeout=15000;server.headersTimeout=10000;server.keepAliveTimeout=5000;
 return server;
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
 const {BEAUTY_LICENSE_PRIVATE_KEY,BEAUTY_LICENSE_REGISTRY,BEAUTY_LICENSE_KEY_ID,BEAUTY_LICENSE_PORT='8091',BEAUTY_LICENSE_EXTRA_KEYS}=process.env;
 if(!BEAUTY_LICENSE_PRIVATE_KEY||!BEAUTY_LICENSE_REGISTRY||!BEAUTY_LICENSE_KEY_ID)throw Error('Set license private-key path, registry path and key id');
 const key=signingKey(await readFile(BEAUTY_LICENSE_PRIVATE_KEY));
 const keys=publicKeys(key,BEAUTY_LICENSE_KEY_ID,BEAUTY_LICENSE_EXTRA_KEYS?JSON.parse(await readFile(BEAUTY_LICENSE_EXTRA_KEYS,'utf8')):{});
 const readRegistry=async()=>{const bytes=await readFile(BEAUTY_LICENSE_REGISTRY);if(bytes.length>8388608)throw Error('Registry too large');return JSON.parse(bytes.toString('utf8'));};
 validateRegistry(await readRegistry());
 const port=Number(BEAUTY_LICENSE_PORT);if(!Number.isInteger(port)||port<1024||port>65535)throw Error('Invalid port');
 createLicenseServer({key,keyId:BEAUTY_LICENSE_KEY_ID,keys,readRegistry}).listen(port,'127.0.0.1',()=>console.log(`License service listening on loopback port ${port}; configure HTTPS reverse proxy.`));
}
