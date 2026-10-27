#!/usr/bin/env python3
"""Validate an already-built reobfuscated JAR and prepare local review/final files. No upload/network/credentials."""
from pathlib import Path
import argparse,datetime,hashlib,json,re,shutil,subprocess,zipfile
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--revision',required=True);p.add_argument('--push-run',type=int,required=True);p.add_argument('--pr-run',type=int,required=True);p.add_argument('--status',choices=('candidate','final'),default='candidate');args=p.parse_args()
assert re.fullmatch('[0-9a-f]{40}',args.revision),'Full source revision required'
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()==args.revision,'Source revision differs'
assert not subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).strip(),'Commit source changes before preparing a delivery'
source=args.jar.resolve();assert source.is_file() and source.stat().st_size<50*1024*1024
subprocess.run(['python3',str(ROOT/'scripts/check-mod-jar.py'),str(source)],cwd=ROOT,check=True)
with zipfile.ZipFile(source) as jar:
 names=jar.namelist();assert len(names)<6000 and sum(i.file_size for i in jar.infolist())<64*1024*1024
 assert not any(n.endswith('.properties') or 'CTE2' in n or '/smoke/' in n or '/optionaltest/' in n for n in names),'Private config/source pack/test driver in JAR'
 assert b'm_91087_' in jar.read('dev/discraft/forge/NativeChat.class'),'Missing pinned SRG Minecraft symbol; JAR is not the reobfuscated artifact'
 for name in names:
  if name.endswith(('.json','.toml','.txt','.tsv','.md')):
   text=jar.read(name).decode('utf-8',errors='replace')
   assert not re.search(r'(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{24,28}\.[A-Za-z0-9_-]{6}\.[A-Za-z0-9_-]{25,}(?![A-Za-z0-9_-])',text),'Credential-pattern scan failed; values suppressed'
 version=re.search(r'\nversion="([^"]+)"',jar.read('META-INF/mods.toml').decode()).group(1)
out=ROOT/'build/delivery';out.mkdir(parents=True,exist_ok=True)
data=source.read_bytes();digest=hashlib.sha256(data).hexdigest();name=f'DisCraft-{version}.jar';target=out/name
if source!=target:shutil.copyfile(source,target)
assert hashlib.sha256(target.read_bytes()).hexdigest()==digest
metadata={'status':args.status,'version':version,'commit':args.revision,'jar':name,'size_bytes':len(data),'sha256':digest,'minecraft':'1.20.1','forge':'47.4.0','java':17,'protocol':4,'reobfuscated_srg_check':True,'artifact_origin':'local_reobfuscated_build','ci_binary_comparison':'not_performed','push_ci':f'https://github.com/Aitchn/DisCraft/actions/runs/{args.push_run}','pr_ci':f'https://github.com/Aitchn/DisCraft/actions/runs/{args.pr_run}','prepared_utc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
(out/'BUILD-INFO.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n')
status='候選檔：仍待 CI／獨立複查核對，不是最終驗收交付。' if args.status=='candidate' else '此檔已完成對應 HEAD 的 CI 與獨立複查核對；仍為預覽 build。'
header=f'''# DisCraft 交付資訊

{status}

- 檔名：`{name}`
- 版本：`{version}`；commit：`{args.revision}`
- 大小：**{len(data):,} bytes**
- SHA256：`{digest}`
- Minecraft 1.20.1／Forge 47.4.0／Java 17／protocol 4；已核對 reobf SRG 符號與包內容。
- [push CI]({metadata['push_ci']})、[PR CI]({metadata['pr_ci']})。
- 本檔來自本機 reobf build；未宣稱與 CI 上傳 JAR 逐位元一致，未重試先前遭拒的 CI artifact 下載。
- 本檔與指南不含現有 token、真實 server config 或使用者提供的 CTE2 ZIP。

---

'''
guide=out/f'DisCraft-使用指南-{version}.md';guide.write_text(header+(ROOT/'docs/使用指南.md').read_text(),encoding='utf-8')
(out/(name+'.sha256')).write_text(digest+'  '+name+'\n')
print(json.dumps({'status':args.status,'jar':str(target),'guide':str(guide),'metadata':metadata},ensure_ascii=False,indent=2))
