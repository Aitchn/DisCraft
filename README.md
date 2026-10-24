# DisCraft — Forge 1.20.1 local mock preview

First implementation targets **Minecraft 1.20.1 / Forge 47.4.0 / Java 17**. This checkout contains a tested loader-independent message/media slice and a Forge client adapter. **The Forge adapter has not compiled or launched in this workspace:** ForgeGradle/Minecraft dependencies are not cached and their network domains are not authorized. No installable Forge mod JAR is claimed.

There is no Discord connection. All messages, custom emoji and media come from an in-process fixture peer. Sending executes the mock transport and produces a separate peer reply; a simulated relay echo is suppressed. No Discord app, bot, OAuth grant, credentials, deployment, release or remote push has been created.

| Feature | Implemented behavior | Verification |
| --- | --- | --- |
| Text in both directions | Unicode text, parsed custom emoji, send/poll transport | Core tests + independent GUI send buttons |
| Unicode/custom emoji | Code-point validation; `:discraft:` PNG and `:dance:` animated GIF definitions and rendering | Model tests; independent viewer custom image preview; Forge rendering pending |
| Still images | Content-sniffed PNG/JPEG, bounded decode, upload filename entry and thumbnail renderer | Core tests; PNG GUI attach/send/receive |
| GIF | Decoded/composited frames, delays, disposal, animation loop; send entry | Core tests; actual GUI animation frame screenshots |
| Chat entry | F8 key binding and title-screen “DisCraft mock” button; scrollable transcript, composer, emoji shortcuts | Forge source implemented; Minecraft execution blocked |
| Policy | Send/receive/media permissions, token bucket, bounded dedup/history, upload confinement, media limits | Core tests |
| Real Discord / relay | Trust boundary and future protocol documented | **Not implemented or connected** |
| Fabric / NeoForge | Shared core has no loader imports | **No adapter implemented** |
| WebP, stickers, video, remote GIF provider, emoji search | Outside this preview | **Not implemented** |

## Build and test the independent slice

Use JDK 17. The checked-in official Forge 1.20.1 branch Wrapper selects Gradle 8.8 and verifies its distribution SHA-256. Its first run requires the already-permitted Gradle distribution domains; subsequent core builds use no third-party dependencies.

```bash
# In the prepared cloud workspace:
source /workspace/discraft-env/activate.sh
use_jdk 17
./gradlew :core:build

# After the wrapper is cached:
./scripts/verify.sh
./scripts/verify.sh --ui
```

`core:coreTest` is an executable, dependency-free behavioral suite wired into `check`/`build`; it is not a JUnit suite. The core JAR is `core/build/libs/core-0.1.0-dev.jar`, **not a Minecraft mod**. `--ui` runs an independent Swing acceptance harness using Xvfb when available. It drives PNG and GIF attach/send controls, observes the mock replies, captures actual rendered animation frames and checks image differences. This harness is a verification tool rather than a second Minecraft adapter.

Saved results: [validation report](docs/VALIDATION.md), [core build](docs/evidence/core-build.txt), [GUI result](docs/evidence/preview-result.txt), [frame 0](docs/evidence/preview-frame0.png), [frame 1](docs/evidence/preview-frame1.png).

## Forge build and usage — pending dependency access

```bash
./gradlew -Pforge :forge:build
./gradlew -Pforge :forge:runClient
# Once dependencies/assets are available, a headless attempt can use:
# headless-run env JAVA_HOME="$JAVA_HOME" ./gradlew -Pforge :forge:runClient
```

The `-Pforge` switch includes the Forge subproject and its plugin repository; omitting it leaves the independent slice buildable without Forge. The mod JAR will embed `core` classes/resources and use ForgeGradle reobfuscation. The official MDK source and wrapper provenance are recorded in [vendor/forge-mdk](vendor/forge-mdk/README.md). The [official Forge downloads page](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html) lists the pinned 47.4.0 target; this does not establish that our adapter builds successfully.

Once a real client can launch:

1. Open **DisCraft mock** on the title screen or press **F8** in game.
2. Scroll to the two fixture messages, including an image and a three-frame GIF.
3. Type Unicode text and insert `:discraft:` / `:dance:` with shortcuts. Press **Send** or Enter.
4. For your own PNG/JPEG/GIF, place a regular file in `run/discraft-uploads/`, enter its filename and choose **Attach file**. Subdirectories, absolute paths and symlinks are rejected. **Demo GIF** attaches a bundled fixture without external files.
5. The attachment status confirms bounded decode. **Send** forwards it to the mock peer; the transcript shows your message and a distinct mock reply. There is no Discord delivery.

These Forge instructions describe implemented source behavior awaiting Minecraft verification. The independent GUI already verifies local attach/send/receive and animation; it cannot establish Minecraft glyph coverage, LWJGL texture behavior or loader correctness. Unicode strings are preserved; emoji/font glyph availability depends on the renderer and font. The independent screenshot has missing supplementary emoji glyphs on this environment.

See [architecture/security](docs/ARCHITECTURE.md) for the trusted relay boundary, policy limits and next integration steps.
