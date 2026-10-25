# Multiplayer bridge checkpoint — local integration passed; review pending

This feature branch starts at verified main `e57ea584a225c888be35d06e1df29efe82702f50`. No implementation has been pushed to main. This is a server/client Forge 1.20.1 vertical slice with successful local dedicated-server/multiple-client validation, not proof of live Discord delivery. Publishing/CI integration and independent review are still pending.

Implemented source so far:

- Optional versioned Forge channel, 16 KiB chunking, 512 KiB aggregate records, 256 KiB bridge files, per-peer ingress throttling and a bounded main-thread admission queue.
- Server-derived player names and canonical event IDs, bounded session/reconnect request replay window, server-side upload validation/decode, bounded worker queue and transcript, player allowlist, lifecycle cleanup and vanilla/no-channel plaintext fallback.
- Client receive assembly/revalidation, bounded decoder/transcript, ACK timeout with no automatic resend, connection-generation guard, server-mode screen presentation and custom emoji assets. Title-screen local mock remains a separately labelled test preview.
- Official Discord Bot REST v10 adapter: fixed API location, Bot-account check, one designated guild text channel, bootstrap cursor plus bounded polling/backoff, 429 retry-after gate, terminal 401/403, no bot/webhook loops, allowed_mentions disabled, multipart image/GIF uploads, official custom emoji markup/CDN decode, bounded HTTP bodies and no redirects. Token comes only from a server environment variable; it is not serialized to players or logged.
- Explicit server-owned test peer and loopback HTTP protocol tests. A two-real-client acceptance driver and controller now pass against a real dedicated server, using the explicit server-owned TEST peer.

Operator configuration is intentionally opt-in: `DISCRAFT_MODE=discord`, JVM `-Ddiscraft.discord.enabled=true`, `DISCRAFT_DISCORD_CHANNEL`, `DISCRAFT_DISCORD_BOT_TOKEN`, and `DISCRAFT_ALLOWED_PLAYERS` (comma-separated UUIDs, or explicit `*`). Live mode also requires online-mode authentication. No real configuration/token has been supplied or used. Local test mode requires a separate smoke JVM flag and never uses a real bot credential.

Current continuation results:

- Fixed launch export by resolving the pinned ForgeGradle 6.0.54 RunConfig with its token helpers. JavaExec is initialized inside exec(), so reading its prepared-task fields was insufficient. The exported command retains only MOD_CLASSES, not arbitrary host environment/credentials, and uses JDK17 with 1 GiB heap per game process.
- Actual dedicated server + Alice/Bob clients: PASS. Alice 21 unique checks and Bob 3 checks, with real game packets, Unicode/PNG/GIF/custom emoji, canonical sender/event IDs, spoof/replay/oversize/out-of-order rejection, rate limits, close/reopen and disconnect/reconnect. Server stopped cleanly; all test processes exited. This uses an isolated loopback offline test profile; no production server or runtime network/security configuration was changed.
- Actual framebuffer images were visually inspected. Network-fed GIF captures differed by 1,958 pixels inside the attachment crop (20,114,180,186), at 1100×800. PNG and static/animated emoji textures were visible.
- Latest `./scripts/verify-forge.sh --client` and packaged-mod check: PASS — 49 core checks, 27 loopback HTTP/wire checks, normal Forge build/reobfJar, and 36 original mock-client checks. REST tests cover send/multipart and receive/emoji/media, cursor retention across transient failures, duplicate suppression, 429 gate, terminal permissions, personal-account rejection, oversized body cancellation, SSRF targets and no redirects. These remain local HTTP contract tests, never real Discord service delivery.

Historical first checkpoint results (superseded where noted):

- First normal `./gradlew -Pforge :core:build :forge:build`: passed, 49 existing core checks and Forge build/reobfJar.
- Next normal build: passed, 49 core plus 17 loopback REST/wire protocol checks. Source later gained additional assertions and implementation refinements; those new assertions have not yet been rerun.
- `exportBridgeLaunch` with the new multiplayer smoke sources: server, Alice and Bob preparation/Java compilation succeeded after correcting the 1.20.1 disconnect API to `clearLevel`.
- `scripts/verify-multiplayer.py`: FAILED before spawning any Minecraft process. The exported JavaExec fields are uninitialized until Forge's run-task setup action executes: executable is null, classpath/args and MOD_CLASSES are empty, workingDir is still the task default. The controller ended with TypeError at Popen. Fix launch export/capture at the correct run-task execution boundary before treating any dedicated or multiplayer check as executed.

Evidence stays local in `/workspace/discraft-bridge-build.log`, `/workspace/discraft-bridge-protocol.log`, `/workspace/discraft-export-launch.log`, `/workspace/discraft-multiplayer.log`, and `build/evidence/multiplayer/*-prepare.log`; no environment logs or generated worlds should be committed. `run-mp/` and build output are ignored.

Remaining: update CI and final user documentation, assess no-channel compatibility with an actual endpoint (not yet tested with a pure vanilla client), inspect security/resource limits with an independent reviewer, then push feature branch/open draft PR and obtain CI/review results. No new PR or CI run is claimed yet. Real Discord requires specifically approved bot setup and a designated test channel; no credential creation, OAuth grant, real message send, deployment, Release or branch deletion has occurred. Gateway/resume, threads/DMs, edits/deletes/reactions, voice/video/stickers/WebP/APNG, guild-wide emoji discovery/search and full Discord parity are outside this stage.
