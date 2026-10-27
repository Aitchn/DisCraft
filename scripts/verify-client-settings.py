#!/usr/bin/env python3
"""Two actual client JVMs in one fresh local profile: defaults, Mods/Config UI, then persisted restart."""
from pathlib import Path
import fcntl,json,os,signal,subprocess,time,zipfile
ROOT=Path(__file__).resolve().parents[1];os.chdir(ROOT)
OUT=ROOT/'build/evidence/client-settings';OUT.mkdir(parents=True,exist_ok=True)
lock=(OUT/'.runner.lock').open('w');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
environment=os.environ.copy()
for key in ('discord_token','discord_channel','DISCRAFT_DISCORD_BOT_TOKEN','DISCRAFT_DISCORD_CHANNEL','DISCRAFT_ALLOWED_PLAYERS','DISCRAFT_MODE'):environment.pop(key,None)
environment.update(LIBGL_ALWAYS_SOFTWARE='true',ALSOFT_DRIVERS='null',LP_NUM_THREADS='2')
with (OUT/'prepare.log').open('w') as output:
 result=subprocess.run(['./gradlew','-Pforge','-PmultiplayerSmoke','-PbridgeRole=Alice',':forge:exportBridgeLaunch','--console=plain'],env=environment,stdout=output,stderr=subprocess.STDOUT,timeout=300)
if result.returncode:raise SystemExit('Client settings launch preparation failed; see prepare.log')
data=json.loads((ROOT/'build/mp-launch-Alice.json').read_text());command=[c for c in data['command'] if c!='-Ddiscraft.multiplayerSmoke=true']
directory=ROOT/('run-mp/client-settings-'+str(time.time_ns()));directory.mkdir(parents=True)
if '--gameDir' in command:command[command.index('--gameDir')+1]=str(directory)
natives=directory/'native-libraries';natives.mkdir();cp=command[command.index('-cp')+1]
for library in cp.split(os.pathsep):
 if not library.endswith('-natives-linux.jar'):continue
 with zipfile.ZipFile(library) as archive:
  for entry in archive.infolist():
   if entry.filename.startswith('linux/x64/') and entry.filename.endswith('.so'):
    assert entry.file_size<64*1024*1024;(natives/Path(entry.filename).name).write_bytes(archive.read(entry))
main=command.index('-cp')+2;command=command[:main]+['dev.discraft.forge.smoke.DesktopBootstrap']+command[main:]
command=command[:1]+['-Dorg.lwjgl.librarypath='+str(natives),'-Ddiscraft.clientSettingsSmoke=true','-Ddiscraft.clientSettingsOutput='+str(OUT)]+command[1:]
child=environment.copy();child.update(data['environment']);process=None
def interrupted(signum,frame):raise KeyboardInterrupt('Client settings acceptance interrupted')
signal.signal(signal.SIGTERM,interrupted)
try:
 for restart in (False,True):
  resultfile=OUT/('restart-result.txt' if restart else 'result.txt');resultfile.unlink(missing_ok=True)
  args=command[:1]+(['-Ddiscraft.settingsRestart=true'] if restart else [])+command[1:]
  with (OUT/('restart.log' if restart else 'client.log')).open('w') as log:
   process=subprocess.Popen(args,cwd=directory,env=child,stdout=log,stderr=subprocess.STDOUT)
   code=process.wait(timeout=240)
  summary=resultfile.read_text();print(summary,flush=True)
  if code!=0 or not summary.splitlines()[-1].startswith('PASS '):raise RuntimeError('Client settings acceptance failed')
finally:
 if process and process.poll() is None:
  process.terminate()
  try:process.wait(timeout=10)
  except subprocess.TimeoutExpired:process.kill();process.wait()
