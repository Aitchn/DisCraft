#!/usr/bin/env python3
"""Select a small dependency closure from already-cached, licensed Minecraft 1.20.1 assets. No downloads."""
import argparse,hashlib,json,os,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--gradle-cache',type=Path,default=Path(os.environ.get('GRADLE_USER_HOME',Path.home()/'.gradle')))
args=parser.parse_args();cache=args.gradle_cache
jar=cache/'caches/forge_gradle/minecraft_repo/versions/1.20.1/client-extra.jar'
asset_root=cache/'caches/forge_gradle/assets';index=asset_root/'indexes/5.json'
OUT=ROOT/'build/local-vanilla-item-assets';OUT.mkdir(parents=True,exist_ok=True)
evidence=ROOT/'build/evidence/vanilla-items';evidence.mkdir(parents=True,exist_ok=True)
def resource(identifier,kind,suffix):
 namespace,path=identifier.split(':',1) if ':' in identifier else ('minecraft',identifier)
 if namespace!='minecraft' or any(x in path.split('/') for x in ('','..','.')):raise ValueError('Unexpected vanilla reference')
 return f'assets/{namespace}/{kind}/{path}{suffix}'
selected={};queue=['minecraft:item/diamond_sword','minecraft:item/stone']
with zipfile.ZipFile(jar) as source:
 version=json.loads(source.read('version.json'))
 if version['id']!='1.20.1':raise SystemExit('Expected cached Minecraft 1.20.1')
 while queue:
  identifier=queue.pop()
  if identifier.startswith(('builtin/','minecraft:builtin/')):continue
  path=resource(identifier,'models','.json')
  if path in selected:continue
  data=source.read(path);selected[path]=data;model=json.loads(data)
  if 'parent' in model:queue.append(model['parent'])
  for override in model.get('overrides',[]):queue.append(override['model'])
  for texture in model.get('textures',{}).values():
   if texture.startswith('#'):continue
   path=resource(texture,'textures','.png');selected[path]=source.read(path)
   if path+'.mcmeta' in source.namelist():selected[path+'.mcmeta']=source.read(path+'.mcmeta')
  if len(selected)>64 or sum(map(len,selected.values()))>4*1024*1024:raise ValueError('Selected asset closure exceeded evidence budget')
 selected['assets/minecraft/lang/en_us.json']=source.read('assets/minecraft/lang/en_us.json')
 entry=json.loads(index.read_text())['objects']['minecraft/lang/zh_tw.json'];blob=asset_root/'objects'/entry['hash'][:2]/entry['hash'];translation=blob.read_bytes()
 if hashlib.sha1(translation).hexdigest()!=entry['hash']:raise ValueError('Cached language object integrity mismatch')
 selected['assets/minecraft/lang/zh_tw.json']=translation
selected['pack.mcmeta']=json.dumps({'pack':{'pack_format':15,'description':'Local vanilla dependency closure for DisCraft verification only'}}).encode()
target=OUT/'vanilla-evidence.zip'
with zipfile.ZipFile(target,'w',compression=zipfile.ZIP_DEFLATED) as archive:
 for path,data in sorted(selected.items()):archive.writestr(path,data)
locale=json.loads(translation)
provenance={'minecraft_version':version['id'],'client_extra_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'asset_index':'5','zh_tw_object_sha1':entry['hash'],'selected_files':{p:hashlib.sha256(d).hexdigest() for p,d in sorted(selected.items())},'labels':{k:locale[k] for k in ('item.minecraft.diamond_sword','block.minecraft.stone')},'distribution':'Only rendered evidence/provenance is deliverable; selected source ZIP stays local outside artifact directories.'}
(evidence/'provenance.json').write_text(json.dumps(provenance,ensure_ascii=False,indent=2)+'\n')
print(f'PASS selected local Minecraft {version["id"]}: {len(selected)} files; names '+json.dumps(provenance['labels'],ensure_ascii=False))
