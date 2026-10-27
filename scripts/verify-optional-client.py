#!/usr/bin/env python3
"""Real optional-peer matrix, isolated loopback servers only; never consumes live Discord variables."""
from pathlib import Path
import fcntl,json,os,signal,socket,subprocess,time,zipfile,argparse,hashlib,urllib.request,shutil
from multiplayer_startup import wait_for_graphics_ready
ROOT=Path(__file__).resolve().parents[1];os.chdir(ROOT)
parser=argparse.ArgumentParser();parser.add_argument('--ncr',action='store_true');args=parser.parse_args()
ncr_jar=None
if args.ncr:
 version='2XUIKIAa';expected='486e6143426ecce7e4784b6f3717d6120de6a29ee73c8fa1d641221f7490e527c13d942ec061c5853a24e1feb8d40126397e405511c3868537567ef1b91f447b'
 ncr_jar=ROOT/'build/compat-research/NoChatReports-FORGE-1.20.1-v2.2.3.jar';ncr_jar.parent.mkdir(parents=True,exist_ok=True)
 if not ncr_jar.exists():
  request=urllib.request.Request('https://api.modrinth.com/v2/version/'+version,headers={'User-Agent':'DisCraft-isolated-compat-test/0.2'})
  with urllib.request.urlopen(request,timeout=20) as response:metadata=json.loads(response.read(1024*1024))
  file=metadata['files'][0];assert file['hashes']['sha512']==expected
  from urllib.parse import urlparse
  assert urlparse(file['url']).scheme=='https' and urlparse(file['url']).hostname=='cdn.modrinth.com'
  with urllib.request.urlopen(urllib.request.Request(file['url'],headers={'User-Agent':'DisCraft-isolated-compat-test/0.2'}),timeout=30) as response:data=response.read(1024*1024)
  assert len(data)==271630 and hashlib.sha512(data).hexdigest()==expected;ncr_jar.write_bytes(data)
 assert ncr_jar.stat().st_size==271630 and hashlib.sha512(ncr_jar.read_bytes()).hexdigest()==expected

OUT=ROOT/('build/evidence/optional-client-ncr' if args.ncr else 'build/evidence/optional-client');OUT.mkdir(parents=True,exist_ok=True)
lock=(OUT/'.runner.lock').open('w');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
environment=os.environ.copy()
for key in ('discord_token','discord_channel','DISCRAFT_DISCORD_BOT_TOKEN','DISCRAFT_DISCORD_CHANNEL','DISCRAFT_MODE','DISCRAFT_ALLOWED_PLAYERS'):environment.pop(key,None)
environment.update(LIBGL_ALWAYS_SOFTWARE='true',ALSOFT_DRIVERS='null',LP_NUM_THREADS='2')
for role in ('server','Alice'):
 with (OUT/(role+'-prepare.log')).open('w') as output:
  result=subprocess.run(['./gradlew','-Pforge','-PmultiplayerSmoke']+(['-PcompatNcr'] if args.ncr else [])+['-PbridgeRole='+role,':forge:exportBridgeLaunch',':forge:optionalClientClasses']+([':forge:createMcpToSrg'] if args.ncr else [])+['--console=plain'],env=environment,stdout=output,stderr=subprocess.STDOUT,timeout=300)
 if result.returncode:raise SystemExit('Optional launch preparation failed; see local prepare log')
if args.ncr:
 # Published mixin refmaps use SRG selectors; the userdev runtime uses named methods.
 # Derive the standard reverse SRG mapping from this pinned ForgeGradle workspace.
 mapping=ROOT/'build/compat-research/srg-to-named.srg';rows=[];left=right=None
 for line in (ROOT/'forge/build/createMcpToSrg/output.tsrg').read_text().splitlines():
  if line.startswith('tsrg2'):continue
  if not line.startswith('\t'):
   values=line.split()
   if len(values)==2:left,right=values
  elif line.startswith('\t') and not line.startswith('\t\t'):
   values=line.split()
   if len(values)==3 and values[1].startswith('('):rows.append(f'MD: {right}/{values[2]} {values[1]} {left}/{values[0]} {values[1]}')
   elif len(values)==2:rows.append(f'FD: {right}/{values[1]} {left}/{values[0]}')
 assert len(rows)>10000;mapping.write_text('\n'.join(rows)+'\n')
optional=[str(ROOT/'forge/build/classes/java/optionalClient'),str(ROOT/'forge/build/resources/optionalClient')]
removed={str(ROOT/f'{project}/build/{suffix}') for project in ('forge','core') for suffix in ('classes/java/main','resources/main')}
def strip_roots(cp):return os.pathsep.join(p for p in cp.split(os.pathsep) if p not in removed and not any(p.startswith(str(ROOT/project/'build/libs')+os.sep) for project in ('forge','core')) and (not args.ncr or 'no-chat-reports' not in p.lower()))
def interrupt(signum,frame):raise KeyboardInterrupt('Optional acceptance interrupted')
signal.signal(signal.SIGTERM,interrupt)
def scenario(name):
 output=OUT/name;output.mkdir(exist_ok=True)
 for path in output.iterdir():
  if path.is_file():path.unlink()
 processes=[];logs=[]
 with socket.socket() as reservation:reservation.bind(('127.0.0.1',0));port=reservation.getsockname()[1]
 def launch(role):
  server=role=='server';bare=(name=='missing_server' and server) or (name!='missing_server' and role in ('bare','observer'))
  data=json.loads((ROOT/('build/mp-launch-'+('server' if server else 'Alice')+'.json')).read_text());command=data['command'];child=environment.copy();child.update(data['environment']);directory=ROOT/f"run-mp/optional-{'ncr-' if args.ncr else ''}{name}-{role}";directory.mkdir(parents=True,exist_ok=True)
  command=[c for c in command if not c.startswith(('-Ddiscraft.bridgeRole=','-Ddiscraft.bridgePort='))]
  flags=['-Ddiscraft.optionalClientSmoke=true','-Ddiscraft.optionalOutput='+str(output),'-Ddiscraft.optionalScenario='+name,'-Ddiscraft.optionalRole='+role,'-Ddiscraft.bridgePort='+str(port)]
  if '--gameDir' in command:command[command.index('--gameDir')+1]=str(directory)
  if bare:
   for mixin_flag in ('--mixin.config',):
    while mixin_flag in command:
     at=command.index(mixin_flag)
     if command[at+1]!='discraft.mixins.json':break
     del command[at:at+2]
   index=command.index('-cp')+1;command[index]=strip_roots(command[index])+os.pathsep+os.pathsep.join(optional)
   for index,value in enumerate(command):
    if value.startswith('-DlegacyClassPath.file='):
     original=Path(value.split('=',1)[1]);target=directory/'optional-legacy-classpath.txt';target.write_text(strip_roots(original.read_text().strip()));command[index]='-DlegacyClassPath.file='+str(target)
   child['MOD_CLASSES']=os.pathsep.join('discraft_optional_test%%'+p for p in optional)
  if args.ncr:flags+=['-Ddiscraft.ncrCompatibility=true','-Dmixin.debug.export=true','-Dmixin.debug.export.filter=net.minecraft.server.network.ServerGamePacketListenerImpl','-Dmixin.env.remapRefMap=true','-Dmixin.env.refMapRemappingFile='+str(mapping)]
  if server and args.ncr:
   mods=directory/'mods';mods.mkdir(exist_ok=True);(mods/ncr_jar.name).unlink(missing_ok=True)
  if server:
   (directory/'eula.txt').write_text('eula=true\n');(directory/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\nenable-rcon=false\nenable-query=false\nmax-players=4\nview-distance=2\nsimulation-distance=2\nlevel-name=optional-fixture-world\nlevel-type=minecraft:flat\ngenerator-settings={{}}\nspawn-protection=0\nspawn-monsters=false\nspawn-animals=false\nspawn-npcs=false\ndifficulty=normal\ngamemode=creative\n')
  else:
   command[command.index('--username')+1]='ObserverTester' if role=='observer' else 'BareTester' if bare else 'EnhancedTester'
   natives=directory/'native-libraries';natives.mkdir(exist_ok=True);cp=command[command.index('-cp')+1]
   for jar in cp.split(os.pathsep):
    if not jar.endswith('-natives-linux.jar'):continue
    with zipfile.ZipFile(jar) as z:
     for entry in z.infolist():
      if entry.filename.startswith('linux/x64/') and entry.filename.endswith('.so'):
       assert entry.file_size<64*1024*1024;(natives/Path(entry.filename).name).write_bytes(z.read(entry))
   main=command.index('-cp')+2;command=command[:main]+[('dev.discraft.optionaltest.' if bare else 'dev.discraft.forge.smoke.')+'DesktopBootstrap']+command[main:]
   flags+=['-Dorg.lwjgl.librarypath='+str(natives),'-Dorg.lwjgl.system.SharedLibraryExtractPath='+str(natives/'fallback')]
  if server and args.ncr:command+=['--mixin.config','discraft.ncr-test.mixins.json']
  command=command[:1]+flags+command[1:];log=(output/(role+'.log')).open('w');logs.append(log);p=subprocess.Popen(command,cwd=directory,env=child,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT);processes.append(p);return p
 try:
  server=launch('server');deadline=time.monotonic()+150
  while 'Done (' not in (output/'server.log').read_text(errors='replace'):
   if server.poll() is not None or time.monotonic()>deadline:raise RuntimeError(name+' server startup failed')
   time.sleep(.25)
  roles=['enhanced'] if name=='missing_server' else ['bare'] if name=='incompatible' else ['bare','enhanced','observer'];clients=[]
  for role in roles:
   p=launch(role);clients.append(p);wait_for_graphics_ready(p,output/(role+'.log'),role)
  deadline=time.monotonic()+240
  while any(p.poll() is None for p in clients):
   if (output/'server-failure.txt').exists():raise RuntimeError((output/'server-failure.txt').read_text())
   if server.poll() is not None or time.monotonic()>deadline:raise RuntimeError(name+' runtime timeout/server exit')
   for role in roles:
    result=output/((role+'-'+name if role in ('bare','observer') else 'enhanced')+'-result.txt')
    if result.exists() and not result.read_text().splitlines()[-1].startswith('PASS '):raise RuntimeError(result.read_text())
   time.sleep(.25)
  for role,p in zip(roles,clients):
   result=(output/((role+'-'+name if role in ('bare','observer') else 'enhanced')+'-result.txt')).read_text();assert p.returncode==0 and result.splitlines()[-1].startswith('PASS '),result;print(name,role,result.splitlines()[-1],flush=True)
  server.stdin.write(b'stop\n');server.stdin.flush();server.wait(timeout=40)
  assert server.returncode==0
  if name!='missing_server':assert (output/'server-result.txt').read_text().splitlines()[-1].startswith('PASS ')
  if name=='incompatible':assert 'Channels [discraft:bridge] rejected their client side version number' in (output/'server.log').read_text(errors='replace'),'Legacy fixture did not fail for the expected DisCraft channel'
 finally:
  for p in reversed(processes):
   if p.poll() is None:
    p.terminate()
    try:p.wait(timeout=10)
    except subprocess.TimeoutExpired:p.kill();p.wait()
  for log in logs:log.close()
for name in (('mixed',) if args.ncr else ('mixed','incompatible','missing_server')):scenario(name)
print('PASS actual optional-client NCR2.2.3 compatibility matrix; loopback only' if args.ncr else 'PASS actual optional-client matrix; no live Discord, no additional content mods',flush=True)
