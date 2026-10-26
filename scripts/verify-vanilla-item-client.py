#!/usr/bin/env python3
"""Capture native Minecraft vs locally rendered original-asset cards. Requires prepared cards; no downloads/sends."""
from pathlib import Path
import json,os,signal,subprocess,zipfile
ROOT=Path(__file__).resolve().parents[1];os.chdir(ROOT)
OUT=ROOT/'build/evidence/vanilla-items';OUT.mkdir(parents=True,exist_ok=True);(OUT/'client-result.txt').unlink(missing_ok=True)
environment=os.environ.copy()
for key in ('discord_token','discord_channel','DISCRAFT_DISCORD_BOT_TOKEN','DISCRAFT_DISCORD_CHANNEL','DISCRAFT_MODE','DISCRAFT_ALLOWED_PLAYERS'):environment.pop(key,None)
environment.update(LIBGL_ALWAYS_SOFTWARE='true',ALSOFT_DRIVERS='null',LP_NUM_THREADS='2')
with (OUT/'client-prepare.log').open('w') as output:
 subprocess.run(['./gradlew','-Pforge','-PmultiplayerSmoke','-PbridgeRole=Alice',':forge:exportBridgeLaunch','--console=plain'],env=environment,stdout=output,stderr=subprocess.STDOUT,check=True,timeout=300)
data=json.loads((ROOT/'build/mp-launch-Alice.json').read_text());command=data['command'];directory=ROOT/'run-mp/vanilla-items-client';directory.mkdir(parents=True,exist_ok=True)
command=command[:1]+['-Ddiscraft.vanillaItemsSmoke=true','-Ddiscraft.vanillaItemsOutput='+str(OUT),'-Ddiscraft.vanillaItemsCards='+str(ROOT/'build/evidence/server-events')]+command[1:]
if '--gameDir' in command:command[command.index('--gameDir')+1]=str(directory)
child=environment.copy();child.update(data['environment']);natives=directory/'native-libraries';natives.mkdir(exist_ok=True)
for library in command[command.index('-cp')+1].split(os.pathsep):
 if not library.endswith('-natives-linux.jar'):continue
 with zipfile.ZipFile(library) as archive:
  for entry in archive.infolist():
   if entry.filename.startswith('linux/x64/') and entry.filename.endswith('.so') and entry.file_size<64*1024*1024:(natives/Path(entry.filename).name).write_bytes(archive.read(entry))
command=command[:1]+['-Dorg.lwjgl.librarypath='+str(natives),'-Dorg.lwjgl.system.SharedLibraryExtractPath='+str(natives/'fallback')]+command[1:]
process=None
def interrupted(signum,frame):raise KeyboardInterrupt('Vanilla item client acceptance interrupted')
signal.signal(signal.SIGTERM,interrupted)
try:
 with (OUT/'client.log').open('w') as output:process=subprocess.Popen(command,cwd=directory,env=child,stdout=output,stderr=subprocess.STDOUT);process.wait(timeout=180)
 result=(OUT/'client-result.txt').read_text()
 if process.returncode!=0 or not result.startswith('PASS '):raise RuntimeError('Native item comparison failed; see local evidence')
 print(result.strip())
finally:
 if process is not None and process.poll() is None:
  process.terminate()
  try:process.wait(timeout=10)
  except subprocess.TimeoutExpired:process.kill();process.wait()
