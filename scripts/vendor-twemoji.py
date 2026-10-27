#!/usr/bin/env python3
"""Reproduce the pinned, byte-identical PNG subset/index from already downloaded official inputs."""
from pathlib import Path
import hashlib,re,struct,zipfile,json
ROOT=Path(__file__).resolve().parents[1];source=ROOT/'build/vendor-input';out=ROOT/'core/src/main/resources/twemoji';out.mkdir(parents=True,exist_ok=True)
PINS={'GraphemeBreakProperty.txt':'d6b51d1d2ae5c33b451b7ed994b48f1f4dc62b2272a5831e7fd418514a6bae89','twemoji.zip':'d91bdab1e9ac66f2935425e539f5ac1cd83a491fa27b2021c77630a0ba8e09ff','emoji-test.txt':'1d8a944f88d7952f7ef7c5167fef3c67995bcae24543949710231b03a201acda','unicode-license.txt':'e7a93b009565cfce55919a381437ac4db883e9da2126fa28b91d12732bc53d96'}
for name,digest in PINS.items():assert hashlib.sha256((source/name).read_bytes()).hexdigest()==digest,name+' pin mismatch'
ranges=[]
for line in (source/'GraphemeBreakProperty.txt').read_text().splitlines():
 m=re.match(r'^([0-9A-F]+)(?:\.\.([0-9A-F]+))?\s*;\s*(Extend|SpacingMark|Prepend)\b',line)
 if m:ranges.append((int(m[1],16),int(m[2] or m[1],16),{'Extend':1,'SpacingMark':2,'Prepend':3}[m[3]]))
ranges.sort();assert len(ranges)<2048 and all(ranges[i-1][1]<ranges[i][0] for i in range(1,len(ranges)))
(out/'grapheme.tsv').write_text(''.join(f'{a:x}\t{b:x}\t{kind}\n' for a,b,kind in ranges))
with zipfile.ZipFile(source/'twemoji.zip') as archive:
 assert len(archive.infolist())<20000 and sum(e.file_size for e in archive.infolist())<256*1024*1024
 names={Path(n).stem:n for n in archive.namelist() if '/assets/72x72/' in n and n.endswith('.png')};images={};rows=[];primary=set();missing=[];group='';qualified={}
 for line in (source/'emoji-test.txt').read_text().splitlines():
  m=re.match(r'^([0-9A-F ]+)\s*;\s*fully-qualified',line)
  if m:
   cs=[format(int(c,16),'x') for c in m[1].split()];key='-'.join(cs);skeleton='-'.join(c for c in cs if c!='fe0f');asset=key if key in names else skeleton
   if asset in names:qualified[skeleton]=asset
 for line in (source/'emoji-test.txt').read_text().splitlines():
  if line.startswith('# group: '):group=line[9:]
  match=re.match(r'^([0-9A-F ]+)\s*;\s*([a-z-]+)\s*#\s*\S+\s+E[0-9.]+\s+(.+)$',line)
  if not match:continue
  codes=[format(int(c,16),'x') for c in match[1].split()];status=match[2];name=match[3]
  if status=='unqualified' and len(codes)==1:continue # Default text-presentation characters need VS16.
  key='-'.join(codes);asset=key if key in names else qualified.get('-'.join(c for c in codes if c!='fe0f'),'-'.join(c for c in codes if c!='fe0f'))
  if asset not in names:missing.append((key,status));continue
  if asset not in images:
   data=archive.read(names[asset]);assert len(data)<65536 and data[:8]==b'\x89PNG\r\n\x1a\n' and struct.unpack('>II',data[16:24])==(72,72)
   images[asset]=data
  category=1 if group=='Animals & Nature' else 2 if group=='Food & Drink' else 3 if group in ('Travel & Places','Activities','Objects') else 4 if group in ('Symbols','Flags') else 0
  is_primary=status in ('fully-qualified','component') and asset not in primary
  if is_primary:primary.add(asset)
  assert len(codes)<=32 and len(name)<=256 and re.fullmatch('[0-9a-f]+(?:-[0-9a-f]+)*',asset)
  rows.append('\t'.join((key,asset,str(category),'1' if is_primary else '0',hashlib.sha256(images[asset]).hexdigest(),name)))
 assert not missing,missing[:10]
 for asset,data in images.items():(out/(asset+'.png')).write_bytes(data)
 (out/'index.tsv').write_text('\n'.join(rows)+'\n',encoding='utf-8')
 for name,target in [('LICENSE','LICENSE-MIT.txt'),('LICENSE-GRAPHICS','LICENSE-GRAPHICS.txt')]:
  original=next(n for n in archive.namelist() if n.endswith('/'+name));(out/target).write_bytes(archive.read(original))
 (out/'LICENSE-UNICODE.txt').write_bytes((source/'unicode-license.txt').read_bytes())
 metadata={'version':'17.0.3','commit':'b6b55fef1e8636b540a6d016a4729ca8cdf2e60b','source':'https://github.com/jdecked/twemoji/tree/v17.0.3','unicode':'https://unicode.org/Public/17.0.0/emoji/emoji-test.txt','input_sha256':PINS,'images':len(images),'image_bytes':sum(map(len,images.values())),'sequences':len(rows),'picker_entries':len(primary),'grapheme_source':'https://unicode.org/Public/17.0.0/ucd/auxiliary/GraphemeBreakProperty.txt','grapheme_sha256':hashlib.sha256((out/'grapheme.tsv').read_bytes()).hexdigest(),'index_sha256':hashlib.sha256((out/'index.tsv').read_bytes()).hexdigest()}
 (out/'manifest.json').write_text(json.dumps(metadata,indent=2)+'\n')
 (out/'NOTICE.txt').write_text('Twemoji 17.0.3 PNG artwork: Copyright Twitter, Inc. and other contributors; maintained by Jason Sofonia, Justine De Caires and Twemoji contributors.\nSource: https://github.com/jdecked/twemoji/tree/v17.0.3\nArtwork: CC BY 4.0, https://creativecommons.org/licenses/by/4.0/ (full LICENSE-GRAPHICS.txt included). Code license: MIT (LICENSE-MIT.txt included); no upstream JavaScript is executed.\nUnicode 17.0 sequence/name/grapheme data: Copyright 2025 Unicode, Inc.; Unicode License v3 in LICENSE-UNICODE.txt.\nChanges: PNG bytes unmodified; selected/indexed and rendered at interface sizes by DisCraft. The index includes explicit qualification aliases; text VS15 remains text.\nThis is the same Twemoji artwork family used by Discord, not a claim of exact Discord release synchronization.\n')
 print(json.dumps(metadata,indent=2))
