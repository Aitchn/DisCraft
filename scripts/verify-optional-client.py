#!/usr/bin/env python3
"""Real optional-peer matrix, isolated loopback servers only; never consumes live Discord variables."""
from pathlib import Path
import fcntl,json,os,signal,socket,subprocess,time,zipfile
from multiplayer_startup import wait_for_graphics_ready
ROOT=Path(__file__).resolve().parents[1];os.chdir(ROOT)
OUT=ROOT/'build/evidence/optional-client';OUT.mkdir(parents=True,exist_ok=True)
lock=(OUT/'.runner.lock').open('w');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
environment=os.environ.copy()
for key in ('discord_token','discord_channel','DISCRAFT_DISCORD_BOT_TOKEN','DISCRAFT_DISCORD_CHANNEL','DISCRAFT_MODE','DISCRAFT_ALLOWED_PLAYERS'):environment.pop(key,None)
environment.update(LIBGL_ALWAYS_SOFTWARE='true',ALSOFT_DRIVERS='null',LP_NUM_THREADS='2')
for role in ('server','Alice'):
 with (OUT/(role+'-prepare.log')).open('w') as output:
  result=subprocess.run(['./gradlew','-Pforge','-PmultiplayerSmoke','-PbridgeRole='+role,':forge:exportBridgeLaunch',':forge:optionalClientClasses','--console=plain'],env=environment,stdout=output,stderr=subprocess.STDOUT,timeout=300)
 if result.returncode:raise SystemExit('Optional launch preparation failed; see local prepare log')
optional=[str(ROOT/'forge/build/classes/java/optionalClient'),str(ROOT/'forge/build/resources/optionalClient')]
removed={str(ROOT/f'{project}/build/{suffix}') for project in ('forge','core') for suffix in ('classes/java/main','resources/main')}
def strip_roots(cp):return os.pathsep.join(p for p in cp.split(os.pathsep) if p not in removed and not any(p.startswith(str(ROOT/project/'build/libs')+os.sep) for project in ('forge','core')))
def interrupt(signum,frame):raise KeyboardInterrupt('Optional acceptance interrupted')
signal.signal(signal.SIGTERM,interrupt)
def scenario(name):
 output=OUT/name;output.mkdir(exist_ok=True)
 for path in output.iterdir():
  if path.is_file():path.unlink()
 processes=[];logs=[]
 with socket.socket() as reservation:reservation.bind(('127.0.0.1',0));port=reservation.getsockname()[1]
 def launch(role):
  server=role=='server';bare=(name=='missing_server' and server) or (name!='missing_server' and role=='bare')
  data=json.loads((ROOT/('build/mp-launch-'+('server' if server else 'Alice')+'.json')).read_text());command=data['command'];child=environment.copy();child.update(data['environment']);directory=ROOT/f'run-mp/optional-{name}-{role}';directory.mkdir(parents=True,exist_ok=True)
  command=[c for c in command if not c.startswith(('-Ddiscraft.bridgeRole=','-Ddiscraft.bridgePort='))]
  flags=['-Ddiscraft.optionalClientSmoke=true','-Ddiscraft.optionalOutput='+str(output),'-Ddiscraft.optionalScenario='+name,'-Ddiscraft.bridgePort='+str(port)]
  if '--gameDir' in command:command[command.index('--gameDir')+1]=str(directory)
  if bare:
   index=command.index('-cp')+1;command[index]=strip_roots(command[index])+os.pathsep+os.pathsep.join(optional)
   for index,value in enumerate(command):
    if value.startswith('-DlegacyClassPath.file='):
     original=Path(value.split('=',1)[1]);target=directory/'optional-legacy-classpath.txt';target.write_text(strip_roots(original.read_text().strip()));command[index]='-DlegacyClassPath.file='+str(target)
   child['MOD_CLASSES']=os.pathsep.join('discraft_optional_test%%'+p for p in optional)
  if server:
   (directory/'eula.txt').write_text('eula=true\n');(directory/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\nenable-rcon=false\nenable-query=false\nmax-players=4\nview-distance=2\nsimulation-distance=2\nlevel-name=optional-fixture-world\nlevel-type=minecraft:flat\ngenerator-settings={{}}\nspawn-protection=0\nspawn-monsters=false\nspawn-animals=false\nspawn-npcs=false\ndifficulty=normal\ngamemode=creative\n')
  else:
   command[command.index('--username')+1]='BareTester' if bare else 'EnhancedTester'
   natives=directory/'native-libraries';natives.mkdir(exist_ok=True);cp=command[command.index('-cp')+1]
   for jar in cp.split(os.pathsep):
    if not jar.endswith('-natives-linux.jar'):continue
    with zipfile.ZipFile(jar) as z:
     for entry in z.infolist():
      if entry.filename.startswith('linux/x64/') and entry.filename.endswith('.so'):
       assert entry.file_size<64*1024*1024;(natives/Path(entry.filename).name).write_bytes(z.read(entry))
   main=command.index('-cp')+2;command=command[:main]+[('dev.discraft.optionaltest.' if bare else 'dev.discraft.forge.smoke.')+'DesktopBootstrap']+command[main:]
   flags+=['-Dorg.lwjgl.librarypath='+str(natives),'-Dorg.lwjgl.system.SharedLibraryExtractPath='+str(natives/'fallback')]
  command=command[:1]+flags+command[1:];log=(output/(role+'.log')).open('w');logs.append(log);p=subprocess.Popen(command,cwd=directory,env=child,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT);processes.append(p);return p
 try:
  server=launch('server');deadline=time.monotonic()+150
  while 'Done (' not in (output/'server.log').read_text(errors='replace'):
   if server.poll() is not None or time.monotonic()>deadline:raise RuntimeError(name+' server startup failed')
   time.sleep(.25)
  roles=['enhanced'] if name=='missing_server' else ['bare'] if name=='incompatible' else ['bare','enhanced'];clients=[]
  for role in roles:
   p=launch(role);clients.append(p);wait_for_graphics_ready(p,output/(role+'.log'),role)
  deadline=time.monotonic()+240
  while any(p.poll() is None for p in clients):
   if (output/'server-failure.txt').exists():raise RuntimeError((output/'server-failure.txt').read_text())
   if server.poll() is not None or time.monotonic()>deadline:raise RuntimeError(name+' runtime timeout/server exit')
   for role in roles:
    result=output/(('bare-'+name if role=='bare' else 'enhanced')+'-result.txt')
    if result.exists() and not result.read_text().splitlines()[-1].startswith('PASS '):raise RuntimeError(result.read_text())
   time.sleep(.25)
  for role,p in zip(roles,clients):
   result=(output/(('bare-'+name if role=='bare' else 'enhanced')+'-result.txt')).read_text();assert p.returncode==0 and result.splitlines()[-1].startswith('PASS '),result;print(name,role,result.splitlines()[-1],flush=True)
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
for name in ('mixed','incompatible','missing_server'):scenario(name)
print('PASS actual optional-client matrix; no live Discord, no additional content mods',flush=True)
