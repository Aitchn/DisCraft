# Dedicated-server bridge configuration

This preview supports one designated **guild text channel** through official Discord Bot REST v10. It polls new human messages and sends messages/files with a bot account. It does not automate personal accounts and does not implement Gateway, DMs, threads or Discord feature parity.

## Explicit server-side opt-in

Live mode requires all of the following, supplied by the operator only after approving the bot setup and test channel:

| Setting | Purpose |
| --- | --- |
| JVM `-Ddiscraft.discord.enabled=true` | Explicit live-adapter opt-in |
| `DISCRAFT_MODE=discord` | Select official Bot REST adapter |
| `DISCRAFT_DISCORD_CHANNEL` | Numeric ID of the designated guild text channel |
| `DISCRAFT_DISCORD_BOT_TOKEN` | Bot secret, injected by the server's secret manager/environment |
| `DISCRAFT_ALLOWED_PLAYERS` | Comma-separated authenticated player UUIDs; empty denies players; `*` explicitly permits all players |
| Dedicated server, `online-mode=true` | Bind player identity to authenticated server connections; live mode is refused on integrated/offline servers |

Keep the bot token off command lines, client machines, game packets, repository files, screenshots and logs. Do not enable HTTP header/debug logging around live credentials. No bot, OAuth grant, credential or live Discord message was created during development. Startup mode/status describes configuration, not proof of Discord reachability.

The approved bot installation needs View Channel, Read Message History, Send Messages and Attach Files for this channel, plus external-emoji permissions where applicable. Reading ordinary guild message content/media through REST also depends on Discord's Message Content access/intent requirements. Upstream permission and authentication failures fail closed; 401/403 become terminal until the operator corrects the setup and restarts. Do not request Administrator merely for this bridge.

Official references: [message API](https://docs.discord.com/developers/resources/message), [Message Content access](https://docs.discord.com/developers/gateway/you-might-not-need-a-privileged-intent), [rate limits](https://docs.discord.com/developers/topics/rate-limits).

## Delivery semantics

- Only the mod composer is forwarded. Sender names and canonical event IDs are generated on the logical server; client-supplied authors/catalogs are rejected. Regular vanilla chat is not forwarded.
- The adapter sends a `[Minecraft name]` prefix and empty `allowed_mentions.parse`, uses a bounded nonce, and does not automatically retry uncertain sends. Close does not unsend. A disconnect before work starts cancels queued work; an already transmitted request can be uncertain.
- Receiving starts at the current latest channel message, deliberately skipping startup backlog. It polls at most four events per page, suppresses duplicate IDs, ignores bot/webhook messages, preserves its cursor across transient failures, and backs off. Cursor/history/replay state are bounded and in memory; restart is not durable recovery or exactly-once delivery.
- 429 `retry_after` blocks early HTTP retries, conservatively across this adapter. A queue-full, rate-limit, rejected upload or uncertain delivery is reported to the sending client. No network exception body, authorization header or bot token is logged by the adapter.
- Discord static/animated custom markup is resolved through bounded official CDN paths. The Minecraft composer accepts native Discord markup; its two emoji buttons use already observed Discord emoji. There is no full guild emoji picker or arbitrary external GIF provider.

## Resource and compatibility bounds

Bridge messages allow 1 attachment, 256 KiB per encoded media item and 512 KiB for the complete serialized record. Packets carry at most 16 KiB chunks. Decode limits remain 1024×1024, 60 frames and 8,388,608 pixels per complete record, including emoji. There are at most 8 custom emoji, 8 concurrent server upload assemblies, 16 queued server jobs, 64 admitted game-thread packet tasks and 64 tracked upload senders. Per-player pacing is a five-message burst, then one/second, with additional packet/byte admission limits. Assemblies expire after 15 seconds. Client ACK wait is 15 seconds; HTTP requests have bounded bodies and deadlines. Bounded queues can reject work under load; this is not a high-volume channel archive.

A server and client retain at most 12 transcript records and 16,777,216 decoded pixels including emoji. The server replay window retains 2,048 recent `(player UUID, request UUID)` keys across reconnect, not across restart. Transfer metadata cannot select files or URLs on the server. Clients upload bytes from their own confined regular-file upload directory; the server revalidates them. Media fetching allows only approved Discord HTTPS CDN paths, rejects local-address resolution and redirects, and never forwards bot authorization to CDN requests.

The network channel accepts Forge ABSENT/vanilla compatibility versions; the server avoids custom packets for peers without it and sends authorized plaintext receive fallback. Client-side composer sends fail when the server bridge is absent/disabled. Pure vanilla interoperability has not been exercised with a vanilla binary; no-channel recipients cannot upload or send through normal chat. Legacy mod protocol version mismatches are rejected rather than guessed.

## Actual validation and remaining limits

Local checks passed 49 core, 27 loopback HTTP/wire and 36 original mock-client assertions. A real Forge dedicated server and Alice/Bob clients passed 21+3 checks: both players' messages/media, actual GIF/static emoji textures, new drafts during network ACK, repeated Send, server-derived IDs/names, spoofing, replay, invalid chunks/bounds, rate limiting, close/reopen and disconnect/reconnect. Actual framebuffer GIF attachment crops differed by 1,958 pixels and screenshots were visually inspected. These game tests use a labelled **server-owned TEST peer**; HTTP tests use a loopback fixture of official REST endpoints. Neither proves live Discord service delivery.

`verify-multiplayer.py` creates a separate ignored `run-mp/` loopback/offline test profile, strips live Discord environment settings, limits game JVM heaps/CPU and shuts down its processes. It does not change an existing server or runtime network policy. Gradle export uses the pinned ForgeGradle RunConfig token helpers because JavaExec is populated only inside its exec action. Only `MOD_CLASSES` is retained from generated run environment; arbitrary host secrets are not exported.

Still unverified: approved live bot/channel send/receive and production permissions, pure vanilla binary compatibility, independent production-launcher installation and exhaustive font glyph coverage. JPEG decoding is covered by core tests; actual multiplayer media tests use PNG/GIF. Gateway/resume, message edits/deletes/reactions, threads/DMs, voice/video/stickers, WebP/APNG, guild-wide discovery/search and durable offline catch-up are not implemented. No deployment or Release is part of this work.
