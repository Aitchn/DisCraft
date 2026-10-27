#!/usr/bin/env python3
"""Isolated native Twemoji evidence; optional pinned ChatPlus fixture download, never live Discord."""
from pathlib import Path
import argparse,fcntl,json,os,signal,socket,subprocess,time,zipfile,hashlib,urllib.request
ROOT=Path(__file__).resolve().parents[1]

parser=argparse.ArgumentParser();parser.add_argument('--chatplus',action='store_true');parser.add_argument('--pending-card',action='store_true');args=parser.parse_args()
os.chdir(ROOT);OUT=ROOT/(('build/evidence/pending-card-chatplus' if args.chatplus else 'build/evidence/pending-card') if args.pending_card else ('build/evidence/chatplus' if args.chatplus else 'build/evidence/twemoji'));OUT.mkdir(parents=True,exist_ok=True)
if args.chatplus:
 # Pinned official published fixtures only, never bundled with DisCraft.
 for slug,version,expected_size,expected_hash in [('chat-plus','T77CVU4z',27070339,'b7141f09fd6c5459078f9b4babe726a6a0c906b17a4d1acc94d2f2ded0993f1626d49a4b81f1633afa5c53f03ed7c4f60485a1d3a93c68c775383c43ea10441f')]:
  target=ROOT/'build/compat-research/chatplus-forge-2.8.1.jar';target.parent.mkdir(parents=True,exist_ok=True)
  if not target.exists():
   with urllib.request.urlopen(urllib.request.Request('https://api.modrinth.com/v2/version/'+version,headers={'User-Agent':'DisCraft-isolated-compat-test/0.2'}),timeout=20)as r:metadata=json.loads(r.read(1024*1024))
   file=metadata['files'][0];assert file['hashes']['sha512']==expected_hash and file['url'].startswith('https://cdn.modrinth.com/')
   with urllib.request.urlopen(file['url'],timeout=40)as r:data=r.read(32*1024*1024)
   assert len(data)==expected_size and hashlib.sha512(data).hexdigest()==expected_hash;target.write_bytes(data)
  assert target.stat().st_size==expected_size and hashlib.sha512(target.read_bytes()).hexdigest()==expected_hash

lock=(OUT/'.runner.lock').open('w');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
(OUT/'result.txt').unlink(missing_ok=True)
environment=os.environ.copy()
for key in ('discord_token','discord_channel','DISCRAFT_DISCORD_BOT_TOKEN','DISCRAFT_DISCORD_CHANNEL','DISCRAFT_ALLOWED_PLAYERS','DISCRAFT_MODE'):environment.pop(key,None)
environment.update(LIBGL_ALWAYS_SOFTWARE='true',ALSOFT_DRIVERS='null',LP_NUM_THREADS='2')
with socket.socket() as reservation:reservation.bind(('127.0.0.1',0));port=reservation.getsockname()[1]
for role in ('server','Alice'):
 with (OUT/(role+'-prepare.log')).open('w') as output:
  result=subprocess.run(['./gradlew','-Pforge','-PmultiplayerSmoke']+(['-PcompatChatPlus'] if args.chatplus and role=='Alice' else [])+['-PbridgeRole='+role,'-PbridgePort='+str(port),':forge:exportBridgeLaunch']+([':forge:createMcpToSrg'] if args.chatplus else [])+['--console=plain'],env=environment,stdout=output,stderr=subprocess.STDOUT,timeout=300)
 if result.returncode:raise SystemExit('Twemoji launch preparation failed; see local log')
if args.chatplus:
 mapping=ROOT/'build/compat-research/chatplus-srg-to-named.srg';rows=[];left=right=None
 for line in (ROOT/'forge/build/createMcpToSrg/output.tsrg').read_text().splitlines():
  if line.startswith('tsrg2'):continue
  values=line.split()
  if not line.startswith('\t'):
   if len(values)==2:left,right=values
  elif not line.startswith('\t\t'):
   if len(values)==3 and values[1].startswith('('):rows.append(f'MD: {right}/{values[2]} {values[1]} {left}/{values[0]} {values[1]}')
   elif len(values)==2:rows.append(f'FD: {right}/{values[1]} {left}/{values[0]}')
 assert len(rows)>10000;mapping.write_text('\n'.join(rows)+'\n')
pending_run=str(time.time_ns()) if args.pending_card else None
processes=[];logs=[]
def interrupted(signum,frame):raise KeyboardInterrupt('Twemoji acceptance interrupted')
signal.signal(signal.SIGTERM,interrupted)
def launch(role):
 data=json.loads((ROOT/('build/mp-launch-'+('server' if role=='server' else 'Alice')+'.json')).read_text());command=data['command'];directory=ROOT/((('run-mp/pending-card-chatplus-' if args.chatplus else 'run-mp/pending-card-')+pending_run+'-' if args.pending_card else ('run-mp/chatplus-' if args.chatplus else 'run-mp/twemoji-'))+role);directory.mkdir(parents=True,exist_ok=True)
 if args.pending_card and role!='server':command=[c for c in command if c!='-Ddiscraft.multiplayerSmoke=true']
 command=command[:1]+(['-Ddiscraft.chatPlusSmoke=true'] if args.chatplus else [])+(['-Dmixin.env.remapRefMap=true','-Dmixin.env.refMapRemappingFile='+str(mapping)] if args.chatplus and role!='server' else [])+['-Ddiscraft.'+('pendingCardSmoke' if args.pending_card else 'twemojiSmoke')+'=true','-Ddiscraft.twemojiOutput='+str(OUT)]+command[1:]
 if '--gameDir' in command:command[command.index('--gameDir')+1]=str(directory)
 child=environment.copy();child.update(data['environment'])
 if role=='server':
  (directory/'eula.txt').write_text('eula=true\n')
  (directory/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\nenable-rcon=false\nenable-query=false\nmax-players=1\nview-distance=2\nsimulation-distance=2\nlevel-name=twemoji-fixture-world\nlevel-type=minecraft:flat\ngenerator-settings={{}}\nspawn-protection=0\nspawn-monsters=false\nspawn-animals=false\nspawn-npcs=false\ndifficulty=peaceful\ngamemode=creative\nmotd=DisCraft isolated Twemoji fixture\n')
 else:
  if '--username' in command:command[command.index('--username')+1]='EmojiTester'
  natives=directory/'native-libraries';natives.mkdir(exist_ok=True);cp=command[command.index('-cp')+1];copied=set()
  for library in cp.split(os.pathsep):
   if not library.endswith('-natives-linux.jar'):continue
   with zipfile.ZipFile(library) as archive:
    for entry in archive.infolist():
     if entry.filename.startswith('linux/x64/') and entry.filename.endswith('.so'):
      if entry.file_size>=64*1024*1024:raise RuntimeError('Native size bound')
      (natives/Path(entry.filename).name).write_bytes(archive.read(entry));copied.add(Path(entry.filename).name)
  if not {'liblwjgl.so','liblwjgl_opengl.so','libglfw.so'}<=copied:raise RuntimeError('Native runtime unavailable')
  main=command.index('-cp')+2;command=command[:main]+['dev.discraft.forge.smoke.DesktopBootstrap']+command[main:]
  command=command[:1]+['-Dorg.lwjgl.librarypath='+str(natives),'-Dorg.lwjgl.system.SharedLibraryExtractPath='+str(natives/'fallback')]+command[1:]
 output=(OUT/(role+'.log')).open('w');logs.append(output)
 process=subprocess.Popen(command,cwd=directory,env=child,stdin=subprocess.PIPE,stdout=output,stderr=subprocess.STDOUT);processes.append(process);return process
try:
 server=launch('server');deadline=time.monotonic()+120
 while 'Done (' not in (OUT/'server.log').read_text(errors='replace'):
  if server.poll() is not None or time.monotonic()>deadline:raise RuntimeError('UI fixture server startup failed')
  time.sleep(.25)
 server.stdin.write(b'gamerule doDaylightCycle false\ntime set day\nweather clear\n');server.stdin.flush()
 client=launch('client');deadline=time.monotonic()+240
 while client.poll() is None:
  if server.poll() is not None:raise RuntimeError('Isolated UI fixture server exited before client completion')
  if time.monotonic()>deadline:raise RuntimeError('Twemoji verification timed out')
  time.sleep(.25)
 summary=(OUT/'result.txt').read_text()
 if client.returncode!=0 or not summary.splitlines() or not summary.splitlines()[-1].startswith('PASS '):
  # Results contain only this isolated fixture's authored assertions (no live configuration/history).
  # Prefix every bounded line so even an exception string cannot become an Actions command.
  print('Fixture result: client_exit='+str(client.returncode),flush=True)
  for line in summary.splitlines()[-12:]:print('FIXTURE_RESULT '+line[:600],flush=True)
  raise RuntimeError('Twemoji acceptance failed; bounded fixture result is printed above')
 server.stdin.write(b'stop\n');server.stdin.flush();server.wait(timeout=40)
 if server.returncode!=0:raise RuntimeError('UI fixture server shutdown failed')
 print(summary.splitlines()[-1])
finally:
 for process in reversed(processes):
  if process.poll() is None:
   process.terminate()
   try:process.wait(timeout=10)
   except subprocess.TimeoutExpired:process.kill();process.wait()
 for output in logs:output.close()
