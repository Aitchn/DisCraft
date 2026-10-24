# CI and validation

The pull request workflow uses Ubuntu 24.04, Temurin JDK 17 and the checked-in Gradle 8.8 Wrapper. Action dependencies are pinned to full commit SHAs. The workflow has read-only repository permissions, no Discord secrets and no deployment/release/merge steps. It runs on pull requests and pushes to main or feat branches. Review the checks on the latest PR head before merging; workflow checks are not a substitute for branch-protection settings.

| Job | Actual command/check | Artifact |
| --- | --- | --- |
| Core tests (JDK 17) | `./gradlew :core:build`; the executable `coreTest` task must complete its behavior assertions | Core test output and Gradle reports |
| Forge build and reobfuscation (JDK 17) | `./gradlew -Pforge :forge:build :forge:reobfJar`; validate packaged core, fixtures, mod metadata and exclusion of smoke classes | Reobfuscated Forge 1.20.1 mod JAR |
| Minecraft client acceptance (JDK 17) | Xvfb/Mesa runs `./scripts/verify-forge.sh --client`; checks a freshly generated acceptance result rather than Minecraft's exit code alone | Actual framebuffer PNGs and check summary, including failure captures when available |

The client job downloads Minecraft's official assets and launches real Forge 47.4.0 / Minecraft 1.20.1 with the test-only `-Psmoke` driver. It uses actual screens, button handlers, EditBoxes, Enter handling and the registered F8 key mapping. It checks mock send/peer reply/echo suppression; PNG and GIF filename uploads and replies; custom emoji; empty text/corrupt file/path traversal errors; duplicate Send/Clear; closing, texture cleanup and a fresh reopened session. It does not simulate physical OS input. The driver is excluded from the distributed JAR.

Artifacts expire after 14 days. A skipped, pending, cancelled or failed job is not a pass. GitHub Actions results must be read for the latest PR head; historical local passes do not establish that CI passed. No branch protection is configured by this workflow. Suggested review gates are all three named jobs above; a maintainer must configure enforcement separately if desired.

## Local provenance

This PR continues local full HEAD `c5af7464fa5f78fd60d4ff19a75465cca77aeb27`, whose original history is preserved in a local backup bundle. That work passed 49 core checks, 25 real-client checks and a normal Forge build/reobfuscation. Two actual framebuffer screenshots differed by 2,068 pixels within animated media. It corrected three extra closing parentheses in the Forge button builders and pinned the resolved ForgeGradle 6.0.54 version.

The previously empty remote required an empty initial main commit solely as a PR comparison base. The implementation commits were replayed onto it for the feature branch, omitting raw environment logs, policy snapshots and old screenshots from every published tree. Commit hashes differ from the original local history. The Gradle wrapper JAR and three tiny bundled image/GIF fixtures are intentional source inputs; generated mod JARs and new evidence belong in workflow artifacts. No original git configuration or credentials are published.

## Limits

This is an in-process local mock, not a Discord bridge. Real Discord/relay delivery, in-world use, dedicated-server classloading, a separately installed production-launcher mod JAR, exhaustive glyph coverage, and JPEG through the Minecraft UI remain unverified. JPEG decoding is covered by core behavior tests. Fabric and NeoForge are deferred. Minecraft may log stock background authentication or audio-device failures in a headless environment; none is evidence of Discord connectivity. No world creation or real credentials are needed for this acceptance.
