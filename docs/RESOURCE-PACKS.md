# Headless resource-pack foundation

The server-only index reads local ZIP files from `config/discraft/resourcepacks`.
`resourcePacks=base.zip,custom.zip` lists low to high priority; `locale=zh_tw`
selects Discord text independently of connected clients. Both settings live in
`config/discraft/server.properties`; there is no environment source or remote download.
Restart is required. Automatic event notifications consume this index; it does not
change ordinary Minecraft chat or load client rendering code.

## Language layers

Discord localization uses installed mod resources as its base, then the operator's
ordered local packs. Minecraft 1.20.1 format **15** is required for external ZIPs.
For each translation key the order is:

1. Selected locale from local packs, highest listed pack first.
2. The installed mods' same selected locale.
3. Local packs' `en_us`, highest listed pack first.
4. Native Forge/server English, then explicit component fallback, then the key.

CN and TW are selected independently; one does not silently substitute the other.
All death/join/quit/advancement notifications, item names, enchantments, lore and
tooltip text share the same server `ComponentText`/resource index. Literal mod text
remains literal. Config selects the bridge locale without changing global Language,
Minecraft client language, original native advancement announcements or hover behavior.

`InstalledModLanguages` uses the pinned common-side Forge APIs `ModList.getModFiles()`
and `IModFile.findResource("assets")`. It only visits direct namespace children and
exactly `lang/<configured locale>.json`; it never loads client I18n/renderers, searches
arbitrary directories, downloads or installs mods, or executes resource content.
Forge 47.4.0 already loads MC/Forge English and loads installed mod English through
`LanguageHook.loadLanguagesOnServer` before `ServerStartingEvent`. English is reused
through the native English fallback at rendering time, not copied or rescanned; this
also covers the earlier about-to-start lifecycle phase where explicit fallbacks apply.
The extra selected-locale index is built once per configured bridge start/restart.

Duplicate selected-locale keys resolve by ascending sorted tuple of mod IDs per
loaded file (later tuple wins), then ascending namespace within that source. Within
one external pack, namespace/path lexical order wins; ZIP entry order does not.
External pack order always outranks these source orders. Native English keeps Forge's
already resolved resource-stack precedence. None of these bridge priorities depends
on the OS mods-directory enumeration order. A higher-priority operator pack can make
any ambiguous key explicit.

Language JSON merges per key; model, texture and font JSON still replace whole files.
Nested translation arguments/siblings remain meaningful, and literal custom names
are not guessed as keys. `%s`, `%1$s`, `%%` and Minecraft numeric printf forms (normalized
to string parameters) are supported. Unsupported format sequences stay visible.
Dynamic NBT/selector/score components are visibly unresolved, never evaluated against
server data. Patchouli/KubeJS-specific markup or arbitrary mod/client callbacks are
not executed or interpreted by this index. Missing mod/client-only locale assets must
be supplied by an operator pack; the index does not manufacture translations.

The index reads ZIP entries directly without extracting, executing, following
URLs, or modifying the game resource manager. It accepts only local ZIP basenames
and rejects symlink files or source-directory ancestors, traversal, absolute and
backslash paths, duplicate entries, missing metadata and incompatible versions.
Bounds apply across the complete ordered import: eight ZIPs, 20,000 entries,
128 MiB expanded bytes including ignored entries, 4 MiB per entry, 64 MiB retained
asset bytes, 64 MiB compressed per ZIP. JSON has a 1 MiB/32-level limit. PNG
Language keys are limited to 512 UTF-16 units, values to 8192, 250,000 keys per
locale, 750,000 keys/64 locales total, and 32 Mi UTF-16 key+value units across the
merged tables (at most 64 MiB of UTF-16 text, excluding separately bounded map
entry overhead). This bounded adaptation admits the supplied pack's two 464-character
guide keys and 177k/179k locale tables. Existing ZIP, JSON, image and tooltip expansion
bounds remain in force. Installed selected-language reads use the same table bounds,
1 MiB per JSON and 64 MiB total selected JSON, at most 4096 loaded source files,
20,000 namespace children and 20,000 matching language files. Physical sources reject
symlinks and use no-follow opens; Forge SecureJar's `union` provider only accepts READ,
so its already-loaded source nodes are checked without following links and read with
that supported option. Only physical, SecureJar union and JAR filesystems are accepted.

PNG dimensions are checked before pixel decoding: at most 4096 per axis and four
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

## Supplied CTE2 language-only pack validation

The user-provided `CTE2-2.1.4-Chinese-Language-Only-CN-TW.zip` was validated locally,
without extracting/executing it or installing CTE2: 6,251,501 compressed bytes,
2,049 entries, 28,176,894 expanded bytes, largest entry 565,303 bytes, format 15.
It contains 1,932 language JSON files in 974 namespaces: 956 CN, 974 TW, two English.
The complete Java importer produced 177,286 CN, 179,022 TW and 43 English unique keys.
The TW content is the package author's automatic conversion; translation quality is
not guaranteed by DisCraft. Different-value duplicate-key override events are CN 384,
TW 383, including shared third-party namespace keys; the documented lexical policy
makes these reproducible. Only small metadata and samples are retained as evidence;
the source ZIP stays ignored outside CI artifact directories and is not redistributed.

`InstalledLanguageTest` uses original synthetic mod-resource roots for reversed mod
order, multi-namespace duplicates, partial and ordered overlays, selected-locale versus
English precedence, numeric placeholders, literal/explicit fallback, symlinks and JSON/
key/text bounds. `InstalledLanguageAcceptance` boots a real dedicated server without
DISPLAY, reads actually installed Forge CN/TW and DisCraft native English, checks partial
overrides and confirms global Language is unchanged. It runs in the existing headless
CI acceptance; it does not claim any CTE2 mods are installed. Optional local verification:

```sh
python3 scripts/verify-server-events.py --language-pack <local-language-only.zip>
```

Only `language-result.txt` and bounded `language-import-metadata.json` are evidence;
the optional user pack is never copied into uploaded artifact folders.

Source behavior was checked against official pinned [Forge LanguageHook](https://github.com/MinecraftForge/MinecraftForge/blob/1.20.1/src/main/java/net/minecraftforge/server/LanguageHook.java),
[server lifecycle ordering](https://github.com/MinecraftForge/MinecraftForge/blob/1.20.1/src/main/java/net/minecraftforge/server/ServerLifecycleHooks.java),
and [ModList](https://github.com/MinecraftForge/MinecraftForge/blob/1.20.1/fmlcore/src/main/java/net/minecraftforge/fml/ModList.java),
plus the environment's actual Forge 47.4.0/SecureJar 2.1.10 APIs. No upstream renderer
or addon code/dependency was introduced.


Preview.8 automatically loads a verified Minecraft 1.20.1 base for enabled, configured authenticated dedicated bridges with item display. The download runs independently of the server tick and adapter queue. Official version metadata supplies hash/size; the known client SHA-1 is additionally pinned. Only the selected locale object is requested from the asset index. The inert client JAR is never executed or redistributed. A verified receipt/cache supports offline restarts; invalid cached bytes fail closed. Vanilla is below loaded-mod resources and administrator ZIPs. Model/texture/font data comes from loaded Forge roots in deterministic mod-ID order, lazily cached with entry/byte limits. Unsupported or malformed mod resources do not silently turn into a vanilla model. Full custom loaders, callbacks and client decorations remain unsupported.

Official resource stacking reference: https://docs.minecraftforge.net/en/1.20.1/concepts/resources/ . Version source: https://piston-meta.mojang.com/mc/game/version_manifest_v2.json .
