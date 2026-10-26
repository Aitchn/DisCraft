# DisCraft — Forge 1.20.1 server bridge preview

DisCraft targets **Minecraft 1.20.1 / Forge 47.4.0 / Java 17**. A dedicated server validates and relays messages between modded clients and an optional **official Discord Bot REST v10 adapter**. Bot credentials are read only by an explicitly enabled, authenticated dedicated server; they are never included in game packets or client configuration.

Local validation has passed a real dedicated server plus Alice/Bob clients, 49 core checks, 27 loopback REST/wire checks and the original 36 client-preview checks. **Live Discord delivery has not been tested:** it needs an approved bot setup and a designated test channel. Test peers and the title-screen mock are explicitly labelled fixtures; they do not prove Discord connectivity.

| Capability | Current scope |
| --- | --- |
| Multiplayer | Server-authoritative player names/event IDs; actual game packets; upload validation, pacing, replay suppression, disconnect/reconnect |
| Text and emoji | Unicode; incoming/outgoing Discord `<:name:id>` / `<a:name:id>` markup, bounded static/animated emoji images; two shortcuts for observed Discord emoji |
| Media | One PNG/JPEG/GIF attachment per bridge message, **256 KiB encoded file limit**; bounded decode and animated Minecraft textures |
| Discord | One designated guild text channel; REST polling for new human messages and multipart bot sends; conservative rate-limit/backoff handling |
| Compatibility | Optional channel; plaintext receive fallback for clients without the channel. Pure vanilla interoperability is **not yet tested** |
| Other Discord features | Gateway/resume, threads/DMs, edits/deletes/reactions, stickers, voice/video, WebP/APNG and guild-wide emoji search/discovery are **not implemented** |

## Build and local checks

Select JDK 17 in `JAVA_HOME`. Gradle 8.8 and ForgeGradle 6.0.54 are pinned; the wrapper verifies its distribution checksum.

```bash
./gradlew :core:build
./gradlew -Pforge :forge:build
python3 scripts/check-mod-jar.py

# Real client acceptance for the separate local mock preview (display/Xvfb needed):
./scripts/verify-forge.sh --client

# Dedicated server plus two actual clients (display/Xvfb needed):
xvfb-run -a -s '-screen 0 1280x900x24 -nolisten tcp' python3 scripts/verify-multiplayer.py
```

The installable preview is `forge/build/libs/discraft-forge-1.20.1-0.1.0-dev.jar`; the core JAR alone is not a mod. Test drivers are excluded from the mod JAR. [CI](docs/CI.md) retains JAR/test artifacts for 14 days.

## Use

Install the same mod JAR on a Forge dedicated server and modded clients. The server bridge defaults to **disabled**. See [server configuration and limits](docs/SERVER-BRIDGE.md) before enabling Discord; never put a bot token in client config, command-line arguments, source, logs or chat.

The current native-chat interaction prototype uses Minecraft's original **T / ChatScreen** and native message history. A compact smiley opens an anchored **GIF / 貼圖 / 表情符號** picker with search, categories, a grid and item source information. Unicode and already available server emoji are local options; GIF currently contains a labelled bundled demonstration, and remote GIF/sticker search is unavailable. No external provider is contacted. The smiley is drawn from original geometric pixel primitives; reference screenshots inform layout only and are not embedded as UI textures.

The filename/Attach/Clear toolbar has been removed. Staged media uses a compact card with only **spoiler** and **remove** controls. Spoilers conceal the local preview; outgoing files preserve the `SPOILER_` filename convention and explicit Discord spoiler metadata, and received spoiler messages are concealed with an explicit local reveal action. Removing a card cancels the staged selection, not a saved file or delivered message. General text/slash commands retain vanilla handling; F8 retains the existing explicit bridge shortcut and safely opens the local preview out of world.

Server-supplied author identities render as **D@display** or **M@playerId**. Explicit selections carry stable Discord IDs or server-validated Minecraft UUIDs, rather than deriving recipients from typed display-name strings. The protocol uses version2; server and modded clients need the same build. Blanket role/everyone suppression remains enabled, with explicit validated Discord user targets scoped separately. Mention routing is limited to identities the server has learned from authenticated players or trusted incoming Discord records. Typing the same label does not grant a ping; unknown/spoofed targets are rejected. This interaction implementation remains subject to independent review.

**Ctrl+V** stages an image when the system clipboard offers pixels; ordinary text paste retains vanilla handling. **F2** copies screenshot pixels as a mod enhancement and retains the original full-resolution screenshot file. Neither action sends automatically. The clipboard copy is capped at 2048 pixels per side; staged uploads are further bounded to 1024 pixels and 256 KiB. Minecraft initializes headless AWT, so desktop clipboard access runs in a small isolated Java helper using the same installed runtime, with bounded requests and no clipboard polling. A missing/busy desktop reports an error and retains vanilla screenshot saving/text paste. Local Linux X11 acceptance exercises real external-process image ownership, text paste, physical F2 with pixel equality against the saved file, picker/card mouse input and stable author selections through a dedicated server. Desktop helper pixel/deadline/cleanup checks and a production-like headless caller are separate checks. Windows/macOS, live Discord delivery, font/chat-mod compatibility, narration and full opacity/fade coverage remain unverified. Current-head CI and independent review remain required.

The title-screen **DisCraft mock** button is still a separate, in-process preview with bundled fixtures. It does not connect to a server or Discord. Unicode is preserved; glyph coverage depends on Minecraft fonts. Server-only/no-channel clients receive a limited plaintext fallback when authorized, but cannot submit through ordinary vanilla chat.

See [architecture](docs/ARCHITECTURE.md), [server/operator guide](docs/SERVER-BRIDGE.md), and [CI/verification limits](docs/CI.md). Fabric, NeoForge and other Minecraft versions are deferred.
