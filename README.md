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

In a connected world, **F8** opens the **SERVER BRIDGE** composer. Only this composer is relayed; ordinary Minecraft chat is not forwarded. Place an image/GIF in the client's `discraft-uploads/`, enter its filename, attach and send. The server independently validates the bytes and derives identity from the actual player connection. A timeout can mean uncertain delivery; there is no automatic resend. Closing the composer does not retract a submitted message.

The title-screen **DisCraft mock** button is still a separate, in-process preview with bundled fixtures. It does not connect to a server or Discord. Unicode is preserved; glyph coverage depends on Minecraft fonts. Server-only/no-channel clients receive a limited plaintext fallback when authorized, but cannot submit through ordinary vanilla chat.

See [architecture](docs/ARCHITECTURE.md), [server/operator guide](docs/SERVER-BRIDGE.md), and [CI/verification limits](docs/CI.md). Fabric, NeoForge and other Minecraft versions are deferred.
