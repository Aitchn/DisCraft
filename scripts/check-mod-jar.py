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
        'dev/discraft/forge/bridge/NativeFallbackChat.class',
        'dev/discraft/forge/bridge/NativeItemComponents.class',
        'dev/discraft/forge/server/VanillaChatRelay.class',
        'dev/discraft/forge/server/NativeChatProjection.class',
        'dev/discraft/forge/bridge/RelayRecord.class',
        'dev/discraft/forge/server/ServerBridge.class',
        'dev/discraft/forge/server/ServerConfig.class',
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
        'dev/discraft/forge/ClientBridge.class',
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
    assert archive.getinfo('assets/discraft/font/ui.otf').file_size<10*1024*1024, 'UI font exceeded packaged resource budget'
    assert required <= names, f'Missing mod contents: {required - names}'
    assert not any(name.endswith('discraft-server.properties') or 'ServerConfigTest' in name for name in names), 'Private config/test leaked into mod JAR'
    assert 'assets/discraft/textures/gui/smiley.png' not in names, 'Reference screenshot icon leaked into mod JAR'
    assert not any('/smoke/' in name or 'BridgeProtocolTest' in name or 'GuildEmojiTest' in name or 'LiveCatalog' in name or 'ServerEventAcceptance' in name or 'NativeHoverServerAcceptance' in name or 'DiscordAuthor' in name or 'NativeItemDataTest' in name or 'ItemFixtures' in name or 'ItemTooltipAcceptance' in name or 'InstalledLanguageAcceptance' in name or 'OptionalServerAcceptance' in name or '/optionaltest/' in name or 'NativeFallbackTest' in name or 'ItemRenderTest' in name for name in names), 'Test driver leaked into mod JAR'
print(f'PASS packaged mod: {jar.name} SHA256={hashlib.sha256(jar.read_bytes()).hexdigest()}')
