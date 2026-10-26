# Headless resource-pack foundation

The server-only index reads local ZIP files from `config/discraft-resourcepacks`.
`resourcePacks=base.zip,custom.zip` lists low to high priority; `locale=zh_tw`
selects Discord text independently of connected clients. Both settings live in
`discraft-server.properties`; there is no environment source or remote download.
Restart is required. Automatic event notifications consume this index; it does not
change ordinary Minecraft chat or load client rendering code.

This stage accepts Minecraft 1.20.1 resource format **15**. Language JSON is merged
per key across namespaces and packs; later definitions win. Model, texture and
font-provider JSON files use whole-file replacement. Selected-locale keys fall
back to imported `en_us`, then the common server's English translation or the
component's explicit fallback, then the translation key. Components keep nested
translation arguments, siblings and literal custom names. `%s`, `%1$s` and `%%`
are supported; unsupported format sequences remain visible. Dynamic NBT/selector/
score components are visibly unresolved; they are never evaluated against server
data or sent as raw NBT. Styling and hover/click payloads are not exported.

The index reads ZIP entries directly without extracting, executing, following
URLs, or modifying the game resource manager. It accepts only local ZIP basenames
and rejects symlink files or source-directory ancestors, traversal, absolute and
backslash paths, duplicate entries, missing metadata and incompatible versions.
Bounds apply across the complete ordered import: eight ZIPs, 20,000 entries,
128 MiB expanded bytes including ignored entries, 4 MiB per entry, 64 MiB retained
asset bytes, 64 MiB compressed per ZIP. JSON has a 1 MiB/32-level limit. PNG
dimensions are checked before pixel decoding: at most 4096 per axis and four
million pixels. A pack with large unused sounds may exceed these conservative
limits; create a smaller presentation pack rather than expecting partial import.
An import failure rejects the complete replacement snapshot with generic diagnostics
that omit server paths and parser contents. Published asset bytes are defensive
copies; the ordered content fingerprint supports cache invalidation.

The [first headless item profile](ITEM-DISPLAY.md) consumes model, texture and bitmap
font metadata from this index. Successful import does not imply support for custom
loaders, client callbacks, OptiFine CIT or all pack fonts. No GPL code/dependency
from InteractiveChat or BlockModelRenderer is included, and this change does not
alter DisCraft's licensing.

`ResourcePackTest` runs without DISPLAY or a game client using synthetic local
packs: priority/key merge, locale fallback, nested entity/item/death names, sibling
text, placeholders, immutable bytes, component cycles/depth, ZIP source/traversal,
JSON depth, expansion, PNG bounds, missing/incompatible metadata and redacted errors.
It runs inside the existing protocol check; no network connection is needed for
the resource/localization cases.

Format and component behavior follow the official Forge [resources documentation](https://docs.minecraftforge.net/en/1.20.1/concepts/resources/)
and [internationalization documentation](https://docs.minecraftforge.net/en/1.20.x/concepts/internationalization/).
