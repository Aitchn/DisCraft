#!/usr/bin/env python3
"""Isolated loopback/offline acceptance; never configures or contacts a real Discord bot."""
from pathlib import Path
from multiplayer_startup import wait_for_graphics_ready
import json, os, signal, socket, subprocess, time, zipfile, platform, fcntl

ROOT=Path(__file__).resolve().parents[1]
os.chdir(ROOT)
# Let the existing finally block clean up child game JVMs on timeout/cancellation.
def interrupted(signum, frame):
    raise KeyboardInterrupt("Multiplayer acceptance interrupted")
signal.signal(signal.SIGTERM, interrupted)
OUT=ROOT/'build/evidence/multiplayer'
OUT.mkdir(parents=True,exist_ok=True)
runner_lock=(OUT/'.runner.lock').open('w')
try:
    fcntl.flock(runner_lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
except BlockingIOError:
    raise SystemExit('Another multiplayer controller owns this evidence directory; no test was started')
(OUT/'result.txt').unlink(missing_ok=True)
(OUT/'clipboard-helper-pid.txt').unlink(missing_ok=True)
for name in ('Alice','Bob'):
    for suffix in ('-result.txt','-done','-sent'):
        (OUT/(name+suffix)).unlink(missing_ok=True)
with socket.socket() as reservation:
    reservation.bind(('127.0.0.1',0)); port=reservation.getsockname()[1]
environment=os.environ.copy()
for secret in ('DISCRAFT_DISCORD_BOT_TOKEN','DISCRAFT_DISCORD_CHANNEL','DISCRAFT_ALLOWED_PLAYERS','DISCRAFT_MODE','discord_token','discord_channel'):
    environment.pop(secret,None)
environment.update(LIBGL_ALWAYS_SOFTWARE='true',ALSOFT_DRIVERS='null',LP_NUM_THREADS='2')
for role in ('server','Alice','Bob'):
    with (OUT/(role+'-prepare.log')).open('w') as output:
        subprocess.run(['./gradlew','-Pforge','-PmultiplayerSmoke','-PbridgeRole='+role,'-PbridgePort='+str(port),':forge:exportBridgeLaunch','--console=plain'],env=environment,stdout=output,stderr=subprocess.STDOUT,check=True,timeout=300)
server_dir=ROOT/'run-mp/server';server_dir.mkdir(parents=True,exist_ok=True)
(server_dir/'eula.txt').write_text('eula=true\n')
(server_dir/'server.properties').write_text(f'''server-ip=127.0.0.1
server-port={port}
online-mode=false
enforce-secure-profile=false
enable-rcon=false
enable-query=false
max-players=4
view-distance=2
simulation-distance=2
level-name=acceptance-world
level-type=minecraft:flat
generator-settings={{}}
spawn-protection=0
spawn-monsters=false
spawn-animals=false
spawn-npcs=false
difficulty=peaceful
gamemode=creative
motd=DisCraft isolated TEST adapter acceptance
''')
# Use a separate X11 JVM for helper pixel/timeout/lifecycle assertions before game input.
alice_launch=json.loads((ROOT/'build/mp-launch-Alice.json').read_text())
alice_command=alice_launch['command'];desktop_cp=alice_command[alice_command.index('-cp')+1]
with (OUT/'desktop-lifecycle.log').open('w') as output:
    subprocess.run([alice_command[0],'-Djava.awt.headless=false','-Xmx128m','-XX:ActiveProcessorCount=1','-cp',desktop_cp,'dev.discraft.forge.smoke.DesktopLifecycleAcceptance'],env=environment,stdout=output,stderr=subprocess.STDOUT,check=True,timeout=25)
assert 'PASS 10 real X11 clipboard/lifecycle checks' in (OUT/'desktop-lifecycle.log').read_text(),'Desktop helper checks did not finish'
with (OUT/'desktop-headless.log').open('w') as output:
    subprocess.run([alice_command[0],'-Djava.awt.headless=true','-Xmx128m','-XX:ActiveProcessorCount=1','-cp',desktop_cp,'dev.discraft.forge.smoke.HeadlessClipboardAcceptance'],env=environment,stdout=output,stderr=subprocess.STDOUT,check=True,timeout=15)
processes=[]; logs=[]
def launch(role):
    launch=json.loads((ROOT/f'build/mp-launch-{role}.json').read_text())
    directory=Path(launch['directory']);directory.mkdir(parents=True,exist_ok=True)
    child_env=environment.copy();child_env.update(launch['environment'])
    output=(OUT/(role+'.log')).open('w');logs.append(output)
    command=launch['command']
    if role!='server':
        assert platform.system()=='Linux' and platform.machine() in ('x86_64','amd64'),'This headless acceptance currently requires Linux x64 natives'
        # Forge early-display may resolve resources while the game layer is being built.
        # Resolve the already-downloaded runtime JAR bytes before either client starts;
        # never share a temporary extraction directory between the two JVMs.
        natives=directory/'native-libraries';natives.mkdir(exist_ok=True)
        main_index=command.index('-cp')+2
        command=command[:main_index]+['dev.discraft.forge.smoke.DesktopBootstrap']+command[main_index:]
        cp=command[command.index('-cp')+1];copied=set()
        for library in cp.split(os.pathsep):
            if not library.endswith('-natives-linux.jar'):continue
            with zipfile.ZipFile(library) as archive:
                for entry in archive.infolist():
                    if not entry.filename.startswith('linux/x64/') or not entry.filename.endswith('.so'):continue
                    assert entry.file_size<64*1024*1024,'Unexpected native-library size'
                    name=Path(entry.filename).name
                    (natives/name).write_bytes(archive.read(entry));copied.add(name)
        assert {'liblwjgl.so','liblwjgl_opengl.so','libglfw.so'}<=copied,'Runtime native libraries missing from exported classpath'
        command=[command[0],'-Dorg.lwjgl.librarypath='+str(natives),'-Dorg.lwjgl.system.SharedLibraryExtractPath='+str(natives/'fallback')]+command[1:]
        print(f'{role}: prepared {len(copied)} per-client runtime native libraries',flush=True)
    p=subprocess.Popen(command,cwd=directory,env=child_env,stdin=subprocess.PIPE,stdout=output,stderr=subprocess.STDOUT)
    processes.append(p);return p
try:
    server=launch('server');deadline=time.monotonic()+120
    while 'Done (' not in (OUT/'server.log').read_text(errors='replace'):
        assert server.poll() is None,'Dedicated server exited; see server.log'
        assert time.monotonic()<deadline,'Dedicated server startup timeout'
        time.sleep(.25)
    alice=launch('Alice');wait_for_graphics_ready(alice,OUT/'Alice.log','Alice')
    print('Alice: graphics ready before launching Bob',flush=True)
    bob=launch('Bob');wait_for_graphics_ready(bob,OUT/'Bob.log','Bob')
    print('Bob: graphics ready; both real clients now running',flush=True)
    deadline=time.monotonic()+250
    last_states={}
    while alice.poll() is None or bob.poll() is None:
        for role,p in (('Alice',alice),('Bob',bob)):
            assert p.poll() is None or p.returncode==0,f'{role} exited with {p.returncode}; see {role}.log'
        for role in ('Alice','Bob'):
            states=[line for line in (OUT/(role+'.log')).read_text(errors='replace').splitlines() if line.startswith('MULTIPLAYER ')]
            if states and states[-1]!=last_states.get(role):
                last_states[role]=states[-1];print(states[-1],flush=True)
            result=OUT/(role+'-result.txt')
            if result.exists():assert result.read_text().splitlines()[-1].startswith('PASS '),result.read_text()
        assert time.monotonic()<deadline,f'Multiple-client integration timeout; last states: {last_states}'
        assert server.poll() is None,'Dedicated server exited during integration'
        time.sleep(.25)
    for role,p in (('Alice',alice),('Bob',bob)):
        result=(OUT/(role+'-result.txt')).read_text();print(role,result,flush=True)
        assert p.returncode==0 and result.splitlines()[-1].startswith('PASS '),result
    helper_pid=int((OUT/'clipboard-helper-pid.txt').read_text())
    def helper_alive():
        try:return b'dev.discraft.forge.ClipboardDesktop' in Path(f'/proc/{helper_pid}/cmdline').read_bytes()
        except FileNotFoundError:return False
    helper_deadline=time.monotonic()+3
    while helper_alive() and time.monotonic()<helper_deadline:time.sleep(.05)
    if helper_alive():
        os.kill(helper_pid,signal.SIGKILL)
        raise AssertionError('Game shutdown left its desktop clipboard helper alive; owned process cleaned up')
    print('PASS game shutdown leaves no owned desktop clipboard helper process',flush=True)
    server.stdin.write(b'stop\n');server.stdin.flush();server.wait(timeout=40)
    assert server.returncode==0,'Dedicated server did not stop cleanly'
    (OUT/'result.txt').write_text('PASS real Forge dedicated server + Alice/Bob game networking; server-owned TEST peer only.\n')
    print('PASS real dedicated server, two clients, Unicode, PNG/GIF/custom emoji, spoof/replay rejection, reconnect and cleanup')
finally:
    for p in reversed(processes):
        if p.poll() is None:
            p.terminate()
            try:p.wait(timeout=10)
            except subprocess.TimeoutExpired:p.kill();p.wait()
    for output in logs:output.close()
