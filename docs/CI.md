# CI and validation

The workflow uses Ubuntu 24.04, Temurin JDK17, Gradle 8.8 and SHA-pinned Actions, with read-only repository permissions. It runs on pull requests and pushes to main/feat branches. No Discord secrets, deployment, merge or Release steps are configured. Check the latest PR head; pending, skipped, failed or cancelled jobs are not passes. Branch-protection enforcement is configured separately by a maintainer, not by this workflow.

| Job | Checks | Artifact |
| --- | --- | --- |
| Core tests | `:core:build`, including 49 executable behavior assertions | Core output/reports |
| Forge build/reobfuscation | `:forge:build :forge:reobfJar`, including 39 local REST/wire protocol checks; validate packaged client/server/core/media classes and exclude test drivers | Installable mod JAR |
| Minecraft client acceptance | Real Xvfb/Mesa client, 36 original mock-preview checks including latch-controlled Clear/send-edit races | Native screenshots and result summary |
| Dedicated server and two clients | Real server and Alice/Bob clients, 59+4 checks through actual game packets, including native chat media, vanilla Enter, ordinary chat and a real slash command | Server/client logs, screenshots and separate result summaries |

The multiplayer controller reserves a loopback port and creates a separate ignored offline test profile. It strips live Discord credentials/config from child environments and selects only the server-owned TEST adapter. This is genuine game networking with a fixture service peer, not live Discord. It does not alter production server/security or runtime network settings. A pure vanilla binary is not part of this test.

Bounds: 30-minute multiplayer job; 25-minute outer process timeout; 300 seconds for each Gradle preparation, 120 seconds for server startup, 250 seconds for client completion, and 40 seconds for clean server shutdown. Each game JVM has a 1 GiB heap and two active processors; software-renderer threads are limited. The controller checks fresh per-client PASS summaries and process exit codes, stops the server normally on success, and terminates owned processes on failures/cancellation. An old summary or a normal Minecraft exit alone cannot establish a pass.

Fresh-client acceptance waits for vanilla terrain/loading presentation to close before opening DisCraft, including after reconnect. The bridge may already have received packets during `ReceivingLevelScreen`; opening early can let vanilla replace the test screen. Five-second phase/screen/bridge diagnostics and the controller's last-state output make interrupted startup distinguishable from a stalled assertion. The join gate was rechecked with both client directories absent. Launch export explicitly builds `:core:jar` so a clean job has core resources and runtime dependencies; compile-only preparation omits fixture resources and leaves the TEST adapter disabled. Smoke-only adapter initialization errors fail immediately rather than degrading into a client timeout.

The HTTP contract tests never use a real bot token or Discord endpoint. They verify bot-account/permission handling, JSON and multipart sends, received Unicode/emoji/media, cursor/backoff/reconnect/dedup, 429 suppression, bounded bodies, SSRF rejection and no redirects. Forge and the original client job both retain the normal build/protocol checks; no previous gate was removed.

Artifacts expire after 14 days. Only test evidence and the mod JAR are uploaded; generated launch environment/configuration, worlds, caches and credentials are excluded. Historical local checks are useful provenance, not evidence that a newly pushed SHA passed CI. See [server scope and limits](SERVER-BRIDGE.md) and [architecture](ARCHITECTURE.md).

Native regressions use actual game keyboard events and latch-controlled local decoder/worker work. They cover immediate Enter, active decode Clear/replacement, current/stale decode errors, Escape, delayed send success/rejection, captionless GIF, disconnect and actual T/F8 keys. Framebuffer palette checks cover PNG/GIF at (chat width, chat scale, line spacing): (320,1,1), (40,1,1), (40,0.5,0.5). Native reflow preserves ordinary message identity/order/signatures/timestamps. TEST rejection is an explicit server test-adapter fixture, not a live Discord failure.
