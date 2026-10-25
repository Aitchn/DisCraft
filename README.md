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

This checkpoint retains the initial filename toolbar while the requested image clipboard, smiley picker, spoiler/remove card and stable D@/M@ mention UX are being implemented. These newer controls are not implemented in this checkpoint.

The current **native-chat prototype** uses Minecraft's original **T / ChatScreen** and chat history. It defaults to **To: Minecraft**, preserving ordinary player chat. Select **To: bridge** for an explicit server bridge message; slash commands keep vanilla routing. **F8** is a shortcut to the native bridge target while connected; out of world it opens the labelled local preview safely. Place a PNG/JPEG/GIF in the client's `discraft-uploads/`, enter its filename beside the chat input and click **Attach**, then send with Enter. **Clear** cancels the pending attachment selection. Enter preserves the draft while an attachment is validating or a send awaits acknowledgement. An empty caption with a validated attachment sends that file; Escape cancels unfinished validation. The server independently validates bytes and derives identity from the actual player connection. A timeout can mean uncertain delivery; there is no automatic resend. Closing chat does not retract a submitted message.

Bridge messages are added to the native `ChatComponent`; a Forge overlay draws bounded emoji/media cells in their reserved native lines. This is a visual prototype awaiting user review, not a claim of complete native-chat compatibility. Minimum chat width and a small line-spacing/scale matrix have game/framebuffer coverage; custom font/resource packs, other chat mods, accessibility narration, opacity/fade edge cases and every input/scroll/scale combination still need validation. Media cache retention is bounded; old native text can remain after its media expires. The older standalone screen remains for the title-screen fixture preview and earlier regression coverage.

The title-screen **DisCraft mock** button is still a separate, in-process preview with bundled fixtures. It does not connect to a server or Discord. Unicode is preserved; glyph coverage depends on Minecraft fonts. Server-only/no-channel clients receive a limited plaintext fallback when authorized, but cannot submit through ordinary vanilla chat.

See [architecture](docs/ARCHITECTURE.md), [server/operator guide](docs/SERVER-BRIDGE.md), and [CI/verification limits](docs/CI.md). Fabric, NeoForge and other Minecraft versions are deferred.
