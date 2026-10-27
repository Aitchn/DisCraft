#!/usr/bin/env python3
"""Headless Forge lifecycle + synthetic event integration; no live credentials or Discord."""
from pathlib import Path
import argparse,fcntl,json,os,signal,socket,subprocess
ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();modes=parser.add_mutually_exclusive_group();modes.add_argument('--startup-failure',action='store_true');modes.add_argument('--items',action='store_true');parser.add_argument('--timing',action='store_true');parser.add_argument('--timing-tag',default='baseline',choices=('baseline','optimized'));parser.add_argument('--vanilla-auto',action='store_true');parser.add_argument('--vanilla-pack',type=Path);parser.add_argument('--language-pack',type=Path);args=parser.parse_args()
if args.timing and not args.items:parser.error('--timing requires --items')
if args.vanilla_auto and not args.items:parser.error('--vanilla-auto requires --items')
if args.vanilla_pack and not args.items:parser.error('--vanilla-pack requires --items')
os.chdir(ROOT);OUT=ROOT/'build/evidence/server-events';OUT.mkdir(parents=True,exist_ok=True)
lock=(OUT/'.runner.lock').open('w');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
result_name='startup-failure-result.txt' if args.startup_failure else 'items-result.txt' if args.items else 'result.txt'
(OUT/result_name).unlink(missing_ok=True)
environment=os.environ.copy()
for key in ('discord_token','discord_channel','DISCRAFT_DISCORD_BOT_TOKEN','DISCRAFT_DISCORD_CHANNEL','DISCRAFT_ALLOWED_PLAYERS','DISCRAFT_MODE'):environment.pop(key,None)
environment.pop('DISPLAY',None)
with socket.socket() as reservation:reservation.bind(('127.0.0.1',0));port=reservation.getsockname()[1]
with (OUT/'prepare.log').open('w') as output:
 result=subprocess.run(['./gradlew','-Pforge','-PmultiplayerSmoke','-PbridgeRole=server','-PbridgePort='+str(port),':forge:exportBridgeLaunch','--console=plain'],env=environment,stdout=output,stderr=subprocess.STDOUT,timeout=300)
if result.returncode:raise SystemExit('Server event launch preparation failed; see local log')
data=json.loads((ROOT/'build/mp-launch-server.json').read_text());command=data['command'];directory=ROOT/'run-mp/server-events';directory.mkdir(parents=True,exist_ok=True)
command=command[:1]+['-Djava.awt.headless=true','-Ddiscraft.serverEventsSmoke=true','-Ddiscraft.serverEventsOutput='+str(OUT)]+command[1:]
if args.startup_failure:command=command[:1]+['-Ddiscraft.serverEventsFailureSmoke=true']+command[1:]
if args.timing:command=command[:1]+['-Ddiscraft.itemTimingSmoke=true','-Ddiscraft.itemTimingTag='+args.timing_tag]+command[1:]
if args.vanilla_auto:command=command[:1]+['-Ddiscraft.vanillaAutoSmoke=true']+command[1:]
if args.items:command=command[:1]+['-Ddiscraft.serverItemsSmoke=true']+command[1:]
if args.language_pack:command=command[:1]+['-Ddiscraft.languagePackEvidence='+str(args.language_pack.resolve())]+command[1:]
if args.vanilla_pack:command=command[:1]+['-Ddiscraft.vanillaItemPack='+str(args.vanilla_pack.resolve())]+command[1:]
child=environment.copy();child.update(data['environment'])
(directory/'eula.txt').write_text('eula=true\n')
(directory/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\nenable-rcon=false\nenable-query=false\nmax-players=1\nview-distance=2\nsimulation-distance=2\nlevel-name=event-fixture-world\nlevel-type=minecraft:flat\ngenerator-settings={{}}\nspawn-protection=0\nspawn-monsters=false\nspawn-animals=false\nspawn-npcs=false\ndifficulty=peaceful\ngamemode=creative\nmotd=DisCraft isolated event fixture\n')
process=None
def interrupted(signum,frame):raise KeyboardInterrupt('Server event acceptance interrupted')
signal.signal(signal.SIGTERM,interrupted)
try:
 with (OUT/('startup-failure.log' if args.startup_failure else 'items.log' if args.items else 'server.log')).open('w') as output:
  process=subprocess.Popen(command,cwd=directory,env=child,stdin=subprocess.DEVNULL,stdout=output,stderr=subprocess.STDOUT)
  process.wait(timeout=150)
 summary=(OUT/result_name).read_text()
 if (not args.startup_failure and process.returncode!=0) or not summary.splitlines()[-1].startswith('PASS '):raise RuntimeError('Server event acceptance failed; see local evidence')
 print(summary.splitlines()[-1])
finally:
 if process is not None and process.poll() is None:
  process.terminate()
  try:process.wait(timeout=10)
  except subprocess.TimeoutExpired:process.kill();process.wait()
