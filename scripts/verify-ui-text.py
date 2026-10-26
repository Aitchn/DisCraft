#!/usr/bin/env python3
"""Offline native UI text evidence at original framebuffer resolution. Never uses live Discord."""
from pathlib import Path
import argparse,fcntl,json,os,signal,socket,subprocess,time,zipfile
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--tag',choices=('before','after'),default='after');args=p.parse_args()
os.chdir(ROOT);OUT=ROOT/'build/evidence/ui-text';OUT.mkdir(parents=True,exist_ok=True)
lock=(OUT/'.runner.lock').open('w');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
(OUT/(args.tag+'-result.txt')).unlink(missing_ok=True)
environment=os.environ.copy()
for key in ('discord_token','discord_channel','DISCRAFT_DISCORD_BOT_TOKEN','DISCRAFT_DISCORD_CHANNEL','DISCRAFT_ALLOWED_PLAYERS','DISCRAFT_MODE'):environment.pop(key,None)
environment.update(LIBGL_ALWAYS_SOFTWARE='true',ALSOFT_DRIVERS='null',LP_NUM_THREADS='2')
with socket.socket() as reservation:reservation.bind(('127.0.0.1',0));port=reservation.getsockname()[1]
for role in ('server','Alice'):
 with (OUT/(role+'-prepare.log')).open('w') as output:
  result=subprocess.run(['./gradlew','-Pforge','-PmultiplayerSmoke','-PbridgeRole='+role,'-PbridgePort='+str(port),':forge:exportBridgeLaunch','--console=plain'],env=environment,stdout=output,stderr=subprocess.STDOUT,timeout=300)
 if result.returncode:raise SystemExit('UI text launch preparation failed; see local log')
processes=[];logs=[]
def interrupted(signum,frame):raise KeyboardInterrupt('UI text acceptance interrupted')
signal.signal(signal.SIGTERM,interrupted)
def launch(role):
 data=json.loads((ROOT/('build/mp-launch-'+('server' if role=='server' else 'Alice')+'.json')).read_text());command=data['command'];directory=ROOT/('run-mp/ui-text-'+role);directory.mkdir(parents=True,exist_ok=True)
 command=command[:1]+['-Ddiscraft.uiTextSmoke=true','-Ddiscraft.uiTextOutput='+str(OUT),'-Ddiscraft.uiTextTag='+args.tag]+command[1:]
 if '--gameDir' in command:command[command.index('--gameDir')+1]=str(directory)
 child=environment.copy();child.update(data['environment'])
 if role=='server':
  (directory/'eula.txt').write_text('eula=true\n')
  (directory/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\nenable-rcon=false\nenable-query=false\nmax-players=1\nview-distance=2\nsimulation-distance=2\nlevel-name=ui-text-fixture-world\nlevel-type=minecraft:flat\ngenerator-settings={{}}\nspawn-protection=0\nspawn-monsters=false\nspawn-animals=false\nspawn-npcs=false\ndifficulty=peaceful\ngamemode=creative\nmotd=DisCraft isolated UI text fixture\n')
 else:
  if '--username' in command:command[command.index('--username')+1]='TextObserver'
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
  if time.monotonic()>deadline:raise RuntimeError('UI text verification timed out')
  time.sleep(.25)
 summary=(OUT/(args.tag+'-result.txt')).read_text()
 if client.returncode!=0 or not summary.splitlines()[-1].startswith('PASS '):raise RuntimeError('UI text acceptance failed')
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
