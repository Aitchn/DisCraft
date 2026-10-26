"""Validate the distributable, including accidental test-driver packaging."""
from pathlib import Path
import hashlib
import zipfile

jar = Path('forge/build/libs/discraft-forge-1.20.1-0.1.0-dev.jar')
with zipfile.ZipFile(jar) as archive:
    names = set(archive.namelist())
    required = {
        'META-INF/mods.toml',
        'dev/discraft/forge/DisCraft.class',
        'dev/discraft/core/ChatSession.class',
        'dev/discraft/forge/bridge/BridgeNetwork.class',
        'dev/discraft/forge/bridge/RelayRecord.class',
        'dev/discraft/forge/server/ServerBridge.class',
        'dev/discraft/forge/server/DiscordBotAdapter.class',
        'dev/discraft/forge/ClientBridge.class',
        'dev/discraft/forge/ClipboardDesktop.class',
        'dev/discraft/forge/ClipboardDesktop$Pixels.class',
        'dev/discraft/forge/DesktopClipboardService.class',
        'dev/discraft/forge/bridge/ActorRef.class',
        'dev/discraft/forge/bridge/Mention.class',
        'assets/discraft/textures/gui/smiley.png',
        'fixtures/dance.gif',
        'fixtures/landscape.png',
        'fixtures/emoji.png',
    }
    assert required <= names, f'Missing mod contents: {required - names}'
    assert not any('/smoke/' in name or 'BridgeProtocolTest' in name for name in names), 'Test driver leaked into mod JAR'
print(f'PASS packaged mod: {jar.name} SHA256={hashlib.sha256(jar.read_bytes()).hexdigest()}')
