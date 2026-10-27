"""Validate the distributable, including accidental test-driver packaging."""
from pathlib import Path
import hashlib
import sys
import re
import zipfile

version=re.search(r"version = '([^']+)'",Path('build.gradle').read_text()).group(1)
jar = Path(sys.argv[1]) if len(sys.argv)>1 else Path(f'forge/build/libs/DisCraft-{version}.jar')
with zipfile.ZipFile(jar) as archive:
    names = set(archive.namelist())
    metadata=archive.read('META-INF/mods.toml').decode()
    assert 'displayName="DisCraft"' in metadata and 'modId="discraft"' in metadata and f'version="{version}"' in metadata, 'Mod naming/version mismatch'
    required = {
        'META-INF/mods.toml',
        'defaults/discraft/server.properties.example',
        'dev/discraft/forge/DisCraft.class',
        'dev/discraft/forge/DisCraftPaths.class',
        'dev/discraft/core/ChatSession.class',
        'dev/discraft/core/TwemojiCatalog.class',
        'dev/discraft/core/ItemMarkers.class',
        'twemoji/index.tsv',
        'twemoji/grapheme.tsv',
        'twemoji/NOTICE.txt',
        'twemoji/LICENSE-GRAPHICS.txt',
        'twemoji/LICENSE-MIT.txt',
        'twemoji/LICENSE-UNICODE.txt',
        'assets/discraft/font/selector.json',
        'dev/discraft/forge/bridge/BridgeNetwork.class',
        'dev/discraft/forge/bridge/NativeFallbackChat.class',
        'dev/discraft/forge/bridge/NativeItemComponents.class',
        'dev/discraft/forge/server/VanillaChatRelay.class',
        'dev/discraft/forge/server/NativeChatProjection.class',
        'dev/discraft/forge/bridge/RelayRecord.class',
        'dev/discraft/forge/server/ServerBridge.class',
        'dev/discraft/forge/server/ServerConfig.class',
        'dev/discraft/forge/server/BridgeDiagnostics.class',
        'dev/discraft/forge/server/EmojiThumbnailCache.class',
        'dev/discraft/forge/server/ItemSnapshot.class',
        'dev/discraft/forge/server/ItemExpansion.class',
        'dev/discraft/forge/server/ItemTooltip.class',
        'dev/discraft/forge/server/ItemTooltipRenderer.class',
        'dev/discraft/forge/server/HeadlessItemRenderer.class',
        'dev/discraft/forge/server/PackBitmapFont.class',
        'dev/discraft/forge/server/ResourcePackIndex.class',
        'dev/discraft/forge/server/LanguageIndex.class',
        'dev/discraft/forge/server/InstalledModLanguages.class',
        'dev/discraft/forge/bridge/GuildEmojiCatalog.class',
        'dev/discraft/forge/ClientGuildEmoji.class',
        'dev/discraft/forge/UiText.class',
        'assets/discraft/font/ui.json',
        'assets/discraft/font/ui.otf',
        'assets/discraft/font/ofl.txt',
        'dev/discraft/forge/server/DiscordBotAdapter.class',
        'dev/discraft/forge/server/DiscordGateway.class',
        'dev/discraft/forge/server/NativeConvertedPresentation.class',
        'dev/discraft/forge/mixin/ServerChatOriginMixin.class',
        'dev/discraft/forge/mixin/ForgeChatCompletionMixin.class',
        'discraft.mixins.json',
        'dev/discraft/forge/ClientBridge.class',
        'dev/discraft/forge/ChatPlusCompat.class',
        'dev/discraft/forge/ClipboardDesktop.class',
        'dev/discraft/forge/ClipboardDesktop$Pixels.class',
        'dev/discraft/forge/DesktopClipboardService.class',
        'dev/discraft/forge/bridge/ActorRef.class',
        'dev/discraft/forge/bridge/NativeAuthorHeader.class',
        'dev/discraft/forge/bridge/Mention.class',
        'dev/discraft/forge/bridge/ItemInline.class',
        'dev/discraft/forge/bridge/NativeItemData.class',
        'dev/discraft/forge/NativeInteractions.class',
        'fixtures/dance.gif',
        'fixtures/landscape.png',
        'fixtures/emoji.png',
    }
    assert b'discraft.discord.enabled' not in archive.read('dev/discraft/forge/server/ServerBridge.class'), 'Obsolete production JVM gate remains'
    assert archive.getinfo('defaults/discraft/server.properties.example').file_size<=8192, 'Template config exceeded runtime bounds'
    assert archive.getinfo('assets/discraft/font/ui.otf').file_size<10*1024*1024, 'UI font exceeded packaged resource budget'
    assert len([n for n in names if n.startswith('twemoji/') and n.endswith('.png')])==3953, 'Pinned emoji inventory missing'
    assert required <= names, f'Missing mod contents: {required - names}'
    assert 'MixinConfigs: discraft.mixins.json' in archive.read('META-INF/MANIFEST.MF').decode(), 'Missing production origin observer configuration'
    assert 'discraft.ncr-test.mixins.json' not in names and not any('nochatreports' in n.lower() for n in names), 'External compatibility fixture leaked into mod JAR'
    assert not any(n.startswith(('com/ebicep/', 'kotlin/', 'thedarkcolour/')) for n in names), 'Optional chat dependency leaked into mod JAR'
    assert not any(name.endswith('.properties') or 'ServerConfigTest' in name or 'ServerConfigInitTest' in name or 'BridgeDiagnosticsTest' in name for name in names), 'Private config/test leaked into mod JAR'
    assert 'assets/discraft/textures/gui/smiley.png' not in names, 'Reference screenshot icon leaked into mod JAR'
    assert not any('/smoke/' in name or 'BridgeProtocolTest' in name or 'GuildEmojiTest' in name or 'LiveCatalog' in name or 'ServerEventAcceptance' in name or 'NativeHoverServerAcceptance' in name or 'DiscordAuthor' in name or 'NativeItemDataTest' in name or 'ItemFixtures' in name or 'ItemTooltipAcceptance' in name or 'InstalledLanguageAcceptance' in name or 'OptionalServerAcceptance' in name or 'ChatPlusServerAcceptance' in name or '/optionaltest/' in name or 'NativeFallbackTest' in name or 'ItemRenderTest' in name for name in names), 'Test driver leaked into mod JAR'
print(f'PASS packaged mod: {jar.name} SHA256={hashlib.sha256(jar.read_bytes()).hexdigest()}')
