# Multiplayer bridge implementation checkpoint (not accepted)

This feature branch starts at verified main `e57ea584a225c888be35d06e1df29efe82702f50`. No implementation has been pushed to main. This is incomplete work for a server/client Forge 1.20.1 vertical slice, not proof of live Discord delivery.

Implemented source so far:

- Optional versioned Forge channel, 16 KiB chunking, 512 KiB aggregate records, 256 KiB bridge files, per-peer ingress throttling and a bounded main-thread admission queue.
- Server-derived player names and canonical event IDs, bounded session/reconnect request replay window, server-side upload validation/decode, bounded worker queue and transcript, player allowlist, lifecycle cleanup and vanilla/no-channel plaintext fallback.
- Client receive assembly/revalidation, bounded decoder/transcript, ACK timeout with no automatic resend, connection-generation guard, server-mode screen presentation and custom emoji assets. Title-screen local mock remains a separately labelled test preview.
- Official Discord Bot REST v10 adapter: fixed API location, Bot-account check, one designated guild text channel, bootstrap cursor plus bounded polling/backoff, 429 retry-after gate, terminal 401/403, no bot/webhook loops, allowed_mentions disabled, multipart image/GIF uploads, official custom emoji markup/CDN decode, bounded HTTP bodies and no redirects. Token comes only from a server environment variable; it is not serialized to players or logged.
- Explicit server-owned test peer and loopback HTTP protocol tests. A two-real-client acceptance driver and controller have been written, but have NOT run successfully.

Operator configuration is intentionally opt-in: `DISCRAFT_MODE=discord`, JVM `-Ddiscraft.discord.enabled=true`, `DISCRAFT_DISCORD_CHANNEL`, `DISCRAFT_DISCORD_BOT_TOKEN`, and `DISCRAFT_ALLOWED_PLAYERS` (comma-separated UUIDs, or explicit `*`). Live mode also requires online-mode authentication. No real configuration/token has been supplied or used. Local test mode requires a separate smoke JVM flag and never uses a real bot credential.

Actually executed before this checkpoint:

- First normal `./gradlew -Pforge :core:build :forge:build`: passed, 49 existing core checks and Forge build/reobfJar.
- Next normal build: passed, 49 core plus 17 loopback REST/wire protocol checks. Source later gained additional assertions and implementation refinements; those new assertions have not yet been rerun.
- `exportBridgeLaunch` with the new multiplayer smoke sources: server, Alice and Bob preparation/Java compilation succeeded after correcting the 1.20.1 disconnect API to `clearLevel`.
- `scripts/verify-multiplayer.py`: FAILED before spawning any Minecraft process. The exported JavaExec fields are uninitialized until Forge's run-task setup action executes: executable is null, classpath/args and MOD_CLASSES are empty, workingDir is still the task default. The controller ended with TypeError at Popen. Fix launch export/capture at the correct run-task execution boundary before treating any dedicated or multiplayer check as executed.

Evidence stays local in `/workspace/discraft-bridge-build.log`, `/workspace/discraft-bridge-protocol.log`, `/workspace/discraft-export-launch.log`, `/workspace/discraft-multiplayer.log`, and `build/evidence/multiplayer/*-prepare.log`; no environment logs or generated worlds should be committed. `run-mp/` and build output are ignored.

Remaining: repair launch harness, run actual dedicated/multiple-client networking and rendered media tests, verify disconnect/replay/spoof/error operations and old mock acceptance, improve/update CI and user documentation, assess no-channel compatibility with an actual endpoint, run final protocol/build suites, inspect security/resource limits, then push feature branch/open draft PR and obtain independent review. No new PR or CI run is claimed yet. Real Discord requires specifically approved bot setup and a designated test channel; no credential creation, OAuth grant, real message send, deployment, Release or branch deletion has occurred. Gateway/resume, threads/DMs, edits/deletes/reactions, voice/video/stickers/WebP/APNG, guild-wide emoji discovery/search and full Discord parity are outside this stage.
