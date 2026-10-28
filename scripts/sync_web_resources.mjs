// 显式同步制作端已校验的目录；保留旧修订供已保存配置和兼容测试使用。
import {readFile,writeFile,mkdir,copyFile} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {createHash} from 'node:crypto';
const source=process.argv[2];if(!source)throw Error('Usage: node scripts/sync_web_resources.mjs <web resources directory>');
const catalog=JSON.parse(await readFile(join(source,'catalog.json'),'utf8'));
if(catalog.resourceCatalogVersion!==1||!catalog.resources.length)throw Error('目录格式错误');
const target=resolve('app/src/main/assets/resource_center');await mkdir(target,{recursive:true});
for(const item of catalog.resources){const name=item.manifest.id+'_'+item.manifest.revision+'.zip';const bytes=await readFile(join(source,name));if(bytes.length!==item.bytes||createHash('sha256').update(bytes).digest('hex')!==item.sha256)throw Error('摘要不符：'+name);await writeFile(join(target,name),bytes);}
for(const name of ['catalog.json','defaults.json'])await copyFile(join(source,name),join(target,name));
console.log('已同步',catalog.resources.length,'份资源；旧修订保留。');
