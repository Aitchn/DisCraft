# DisCraft — Forge 1.20.1 server bridge preview

Minecraft **1.20.1**, Forge **47.4.0**, Java **17**. Install the mod on the dedicated
server; the client mod is optional and adds the native picker, images, clipboard,
Twemoji and stable author-selection enhancements. Other content mods may still
require matching client installation. The protocol is **4**; explicit incompatible
versions are rejected, absent endpoints are supported.

See the [繁體中文安裝／使用指南](docs/使用指南.md) for actual config, installation,
`%i` / `%item`, event switches and resource-pack placement. Discord credentials
and channel are read only from server-local `config/discraft/server.properties`,
with no environment fallback. A dedicated-server startup creates a commented,
disabled empty-credential template only when neither the new nor legacy config
exists. Existing operator data is never overwritten. See
[the empty example](examples/discraft/server.properties.example) for manual setup.
Production requires an authenticated dedicated server and `enabled=true`; there
is no extra JVM enable switch. Clients and integrated servers do not initialize
this server config or connect to Discord.

Ordinary native chat and configured join/quit/death/advancement/lifecycle notices
are server-side. `%item` and `%i` use one authoritative main-hand snapshot with
safe native hover and an adaptive Discord tooltip PNG, not full NBT or a fixed
model card. Native cancellation/moderation/filter ambiguity fails closed without
rewriting the signed body or automatically retrying delivery.

Twemoji **17.0.3** is bundled and checksum pinned, including artwork CC BY 4.0,
MIT and Unicode notices. It is the same artwork family used by Discord, not an
exact Discord release synchronization claim. Guild static/animated emoji remain
a separate bounded server-owned catalog. GIF provider/sticker search is disabled;
explicit local TEST/demo fixtures are never a real provider.

```bash
./gradlew :core:build
./gradlew -Pforge :forge:build :forge:reobfJar
python3 scripts/check-mod-jar.py
```

The distributable is `forge/build/libs/DisCraft-0.2.0-preview.4.jar`; no
separate core JAR is needed. Smoke/test drivers and local credentials are excluded.
[CI](docs/CI.md) retains JAR and test evidence. Full client/native tests require a
display or Xvfb; production headless servers do not.

Automated verification uses isolated real Forge server/client worlds and local
REST/media receivers. Prior scoped live demonstrations do not establish complete
human ingress or production online-session cryptography coverage. Pure vanilla,
arbitrary modpacks and Windows/macOS/Wayland clipboard are not verified. See
[server limits](docs/SERVER-BRIDGE.md), [resource packs](docs/RESOURCE-PACKS.md),
[item display](docs/ITEM-DISPLAY.md) and [Twemoji provenance](docs/TWEMOJI.md).

Configuration and local imports are grouped under `config/discraft/`: `server.properties`, `resourcepacks/`, and client `uploads/`. New paths win independently; absent new paths use the old server/config pack/upload locations with a redacted warning. Nothing is automatically moved or overwritten; invalid new paths never silently fall back. See the migration table in the Chinese guide.
