# DisCraft — Forge 1.20.1 local mock preview

First implementation targets **Minecraft 1.20.1 / Forge 47.4.0 / Java 17**. The Forge adapter now compiles, reobfuscates, and runs in the real Minecraft client under Xvfb/Mesa. The mod JAR is `forge/build/libs/discraft-forge-1.20.1-0.1.0-dev.jar`. The 49 core checks and 25 client acceptance checks pass. This is a local mock preview; world/server integration and a separately installed production launcher have not been tested.

There is no Discord connection. All messages, custom emoji and media come from an in-process fixture peer. Sending executes the mock transport and produces a separate peer reply; a simulated relay echo is suppressed. This preview requires no Discord app, bot, OAuth grant or credentials. It performs no Discord delivery.

| Feature | Implemented behavior | Verification |
| --- | --- | --- |
| Text in both directions | Unicode text, parsed custom emoji, send/poll transport | Core tests + independent GUI send buttons |
| Unicode/custom emoji | Code-point validation; `:discraft:` PNG and `:dance:` animated GIF definitions and rendering | Core checks and actual Minecraft custom texture rendering; font glyph coverage varies |
| Still images | Content-sniffed PNG/JPEG, bounded decode, upload filename entry and thumbnail renderer | Core tests; PNG GUI attach/send/receive |
| GIF | Decoded/composited frames, delays, disposal, animation loop; send entry | Core tests; actual GUI animation frame screenshots |
| Chat entry | F8 key binding and title-screen “DisCraft mock” button; scrollable transcript, composer, emoji shortcuts | Actual Minecraft widget handlers and F8 key-mapping dispatch; close/reopen and duplicate actions pass |
| Policy | Send/receive/media permissions, token bucket, bounded dedup/history, upload confinement, media limits | Core tests |
| Real Discord / relay | Trust boundary and future protocol documented | **Not implemented or connected** |
| Fabric / NeoForge | Shared core has no loader imports | **No adapter implemented** |
| WebP, stickers, video, remote GIF provider, emoji search | Outside this preview | **Not implemented** |

## Build and test the independent slice

Use JDK 17. The checked-in official Forge 1.20.1 branch Wrapper selects Gradle 8.8 and verifies its distribution SHA-256. Its first run downloads the Gradle distribution; subsequent core builds use no third-party dependencies.

```bash
# Select JDK 17 in JAVA_HOME before building.
./gradlew :core:build

# After the wrapper is cached:
./scripts/verify.sh
./scripts/verify.sh --ui
```

`core:coreTest` is an executable, dependency-free behavioral suite wired into `check`/`build`; it is not a JUnit suite. The core JAR is `core/build/libs/core-0.1.0-dev.jar`, **not a Minecraft mod**. `--ui` runs an independent Swing acceptance harness using Xvfb when available. It drives PNG and GIF attach/send controls, observes the mock replies, captures actual rendered animation frames and checks image differences. This harness is a verification tool rather than a second Minecraft adapter.

See [CI and validation](docs/CI.md) for checks, artifacts and verification limits. Historical environment logs and screenshots are retained in the local handoff rather than published in source history.

## Forge build and usage

```bash
./gradlew -Pforge :forge:build
./gradlew -Pforge :forge:runClient
# Automated real-client acceptance under Xvfb in the prepared environment:
./scripts/verify-forge.sh --client
```

The `-Pforge` switch includes the Forge subproject and its plugin repository; omitting it leaves the independent slice buildable without Forge. The mod JAR embeds `core` classes/resources and uses ForgeGradle reobfuscation. ForgeGradle is pinned to the successfully resolved version 6.0.54. `-Psmoke` includes an opt-in client acceptance driver, excluded from all mod JARs. The official MDK source and wrapper provenance are recorded in [vendor/forge-mdk](vendor/forge-mdk/README.md).

In the client:

1. Open **DisCraft mock** on the title screen or press **F8** in game.
2. Scroll to the two fixture messages, including an image and a three-frame GIF.
3. Type Unicode text and insert `:discraft:` / `:dance:` with shortcuts. Press **Send** or Enter.
4. For your own PNG/JPEG/GIF, place a regular file in `run/discraft-uploads/`, enter its filename and choose **Attach file**. Subdirectories, absolute paths and symlinks are rejected. **Demo GIF** attaches a bundled fixture without external files.
5. The attachment status confirms bounded decode. **Send** forwards it to the mock peer; the transcript shows your message and a distinct mock reply. There is no Discord delivery.

Minecraft acceptance produces CI artifacts with inbound PNG/GIF captures, text/custom emoji, PNG/GIF round trips, errors, and reopening. See [CI and validation](docs/CI.md). The acceptance driver invokes real widget handlers and dispatches the registered F8 mapping; it does not simulate OS mouse/keyboard hardware. Unicode strings are preserved; glyph availability depends on Minecraft fonts. Real Discord delivery, in-world use, dedicated servers, and independent production-launcher installation remain unverified.

See [architecture/security](docs/ARCHITECTURE.md) for the trusted relay boundary, policy limits and next integration steps.
