# Native UX work checkpoint

Remote base: a2736c87d578429d68dc2b4a4022bea00be0a5ab, feat/server-discord-bridge. PR2 stays Draft. No merge, deployment, release, live Discord setup/messages or PR-metadata retry.

All three exact Library references were materialized through the current resolved-reference flow and visually inspected on this executor. Source files are under /workspace/discraft-native-ux-references, Library IDs libfile_90b63d3a792c8191b9270d6ca798c6b8, libfile_073a361d6dc88191971dd29b9b1cfa86, libfile_02f2563125c88191af5b216a767961b4. Identity/version xattrs verified. No user guild/server assets copied into the project.

This WIP adds ActorRef (Minecraft UUID or Discord snowflake, canonical display) and explicit Mention spans to wire protocol2, with legacy constructors for source compatibility. Server derives sender identity and validates mention targets against its bounded trusted identity registry; actor spoofing in drafts is rejected. Bot serialization retains parse=[] mention protection and uses a bounded explicit user list only for structured Discord mention targets. Emoji conversion remaps mention spans; raw display-name strings do not create structured mentions. The TEST peer uses a clearly synthetic Discord identity. Forge compileJava passed; the changed wire/mention behavior is NOT fully tested and NOT pushed yet.

Not implemented: clicking author names, clipboard Ctrl+V, F2 clipboard write, smiley/picker UI, spoiler/remove preview and receive conceal/reveal. Initial filename toolbar is still present and must be replaced. No new UX screenshot exists. User requirements are not complete.

Intended stages: stable identity/token tests plus author-click binding; user-gesture-only image clipboard staging and F2 copy with vanilla file save retained; native anchored picker following inspected layout (GIF/sticker/emoji tabs, search/category rail/grid/source footer); staged spoiler/remove card and proper SPOILER_ attachment send/receive concealment. No external GIF/sticker catalog or new network permissions.

CI native-library fix a2736c8 pre-extracts approved runtime Linux x64 native JARs per role with explicit LWJGL paths. Its local server+two-client run passed59+4. Parent reports PR40246403022 succeeds, push40246399711 fails captionless GIF regression phase10, artifact12619007552; failure artifact not inspected here yet. Candidate is ACK-before-client-decode test timing: phase10 waits only sending=false before inspecting asynchronously decoded history. Verify evidence before fixing; do not mask by blind rerun.

Executor commands were observed working despite parent's environment-disconnected callback; broker recovery status is uncertain. No local game/Gradle process is intentionally running. Preserve this branch and WIP before any recovery/new writer.
