# Server item presentation: Discord tooltips and native hover

Set `itemDisplay=true` in the private server config to expand `%item` in DisCraft
native chat. Existing configurations with no itemDisplay key remain off; newly generated templates set itemDisplay=true. The server copies the
authenticated sender's **main hand on the game thread** when upload finishes;
the offhand and client-supplied text cannot select another stack. Workers receive
only an immutable appearance whitelist and localized semantic tooltip lines: registry ID, localized/custom name, root
font ID, count, CustomModelData, damage fraction/flag, handedness, known leather or
potion tint and enchanted flag. A separate native-only tooltip DTO contains an
allowlist of safe item detail. No original stack/NBT, book pages, container contents
or arbitrary hover/click actions are retained or transmitted.

Up to eight markers outside selected mention labels expand to `[name ×count]` and
share one content-sized tooltip-style PNG **on Discord**. Minecraft displays a styled inline name with
the original native item tooltip; it does not automatically expand that PNG or
open another Screen. A plain native T message containing `%item` explicitly routes
to the bridge when available; ordinary text remains vanilla chat. A server with
item display disabled rejects explicit markers. Empty hand expands to `[Empty hand]` without a fabricated
image. A marker within a selected actor label stays literal. Combining an item
display with an uploaded attachment is rejected; expansion over the existing text
budget is rejected. Native game text keeps the actual name; only generated Discord
literals escape Markdown, neutralize `@` and prevent emoji-alias substitution.
Existing explicitly selected mentions keep their exact IDs with separately remapped
offsets. User message formatting and the `<Aitchn> text` prefix remain unchanged.

## Minecraft native components and hover

The item label uses native component styling and a native `SHOW_ITEM` hover,
not a screenshot or separate viewer. Server rarity supplies the base color;
explicit custom name color/format/font and translation components retain their
meaning and inherit normally. Counts above one appear inline. Wrapping, tooltip
positioning, scaling and scrolling remain Minecraft's own ChatComponent/ChatScreen
behavior. Item names carry no click action. The existing author mention click and
ordinary image behavior remain separate.

Protocol **4** adds up to eight server-authoritative UTF-16 item ranges. Client
drafts containing any item metadata are rejected; main-hand capture happens only
on the server game thread. Each immutable native DTO is at most 8,192 characters
of canonical, type-checked NBT, with depth at most sixteen. It permits only safe
display name/lore/color, damage/CMD, capped enchantment lists, capped numeric
attribute modifiers, unbreakable/hide flags and a base potion identifier. Lore
caps at eight safe component strings of 512 characters each; name JSON caps at
2,048. Optional detail is dropped to fit the total limit. Name/lore components
allow bounded literals, translation keys/arguments/siblings and formatting only;
selectors, scores, NBT queries, click/insertion and nested hover actions are absent.
Attribute internal names are replaced by a fixed tooltip label. No book body,
container inventory, arbitrary mod NBT or server configuration enters the DTO.
List-valued `AttributeModifiers` presence is preserved even when explicitly empty,
all entries are filtered or the tooltip exceeds its size budget: downgrading clears
the list instead of removing the key. This preserves the pinned 1.20.1 override
semantics and cannot restore fabricated default weapon damage/speed. A non-list
source field follows native absent-key behavior; malformed list elements become a
canonical empty override without carrying their original contents. Wire validation
still requires canonical types. Headless regressions compare original/reconstructed
stacks using actual pinned `getAttributeModifiers` and `getTooltipLines`, including
the size-budget path, alongside the official [Forge 1.20.1 ItemStack patch](https://raw.githubusercontent.com/MinecraftForge/MinecraftForge/1.20.1/patches/minecraft/net/minecraft/world/item/ItemStack.java.patch).
The Discord record carries neither this DTO nor Minecraft-only hover semantics;
Discord still receives its rendered item PNG and safe text. This does not claim
Discord supports arbitrary custom text hover.

Vanilla advancement announcements already have the requested inline component,
frame color and title/description hover. DisCraft does not re-add or flatten them
on the Minecraft side. Enabling Discord advancement notices creates only the
separate Discord delivery. Task/goal/challenge styles remain vanilla; no green
color is forced by DisCraft. Native component reflow rewrites only owned bridge
messages and retains other vanilla messages.

`verify-native-hover.py` uses a real isolated Forge server/client, physical T,
Enter, hover and author clicks. It checks wrapped gold custom item text, native
item detail, original green task and purple challenge hover, move-out dismissal,
the same ChatScreen throughout hovering/clicking, scale two/three and scrolling,
spoofed client metadata rejection, retained normal images and no duplicated
advancement announcements while Discord notices are enabled. Its recording TEST
recipient verifies two advancement notices and two PNG-bearing sends; there is
no real Discord send. Required client mods/resources still determine native
mod-specific tooltip behavior; the allowlist is not a complete arbitrary-NBT mirror.

## Discord tooltip images

Discord now receives a separately drawn text tooltip, with a near-black purple background,
a thin purple gradient border and item-derived text styles. The previous fixed 288×256
model card is not the default presentation. No screenshot or cropped reference pixels
are embedded. Native Minecraft inline item rendering and protocol 4 are unchanged.

The explicit profile uses ordinary tooltip visibility for the allowlisted fields:
rarity/custom/translatable name and styles, enchantment names/levels (including curses
and enchanted books), sanitized lore, numeric equipment-slot attributes and unbreakable.
It additionally shows **advanced damaged durability** (`remaining / maximum`) only when
pinned 1.20.1 `isDamaged()` is true. It omits advanced item IDs and NBT counts. `HideFlags`
suppresses enchantments (1), modifiers (2), unbreakable (4) and book additional data (32)
as appropriate. Lore follows vanilla visibility. Pinned 1.20.1 native-oracle checks
also cover explicit NBT modifiers using the vanilla attack UUID: they retain the
numeric modifier without default player/enchantment totals, while default item
modifiers add those totals. No UUID behavior was changed based on recollection. Unbreakable stacks follow vanilla's
non-damageable behavior. Base attack totals use the authoritative player's base values
and allowed enchantments. Explicit or filtered-empty AttributeModifiers still overrides
default item attributes, including after the existing native DTO budget downgrade.

The server first sanitizes the existing whitelist, then builds immutable localized
semantic runs on the game thread. The Discord renderer receives these runs rather
than an original stack or native SNBT DTO. Arbitrary item/mod `appendHoverText`, Forge
ItemTooltipEvent callbacks, book pages, container contents, block/entity NBT, destroy/place
lists, potion-effect callbacks and other additional item-specific sections are outside
this profile. They are not executed to collect extra data. This is an explicitly bounded
profile, not a claim of all mod tooltip compatibility.

Names, lore, nested translation arguments/siblings and numbered `%1$s` substitutions
retain color, font, bold/italic, underline and strike styles. Obfuscated glyphs use a
non-revealing deterministic placeholder. Language selection, per-key merges and fallback
follow the configured [resource-pack stack](RESOURCE-PACKS.md). Supported bitmap/space/
reference fonts use their imported glyphs. Otherwise bundled OFL Noto CJK TC is used,
without a font-source footer or extra padding. Fallback use stays in internal result/
acceptance metadata; unsupported glyphs still use `?` plus a visible Unicode
codepoint notice, never an implicit system font. The two current examples use cached
vanilla en_us/zh_tw text and bundled CJK glyphs, not imported complete vanilla fonts.

Every glyph is measured using the same font/style as drawing. Content wraps without
ellipsis or dropping digits, at most 768×1536 pixels and 60 content rows, with at most
64 semantic lines/8192 characters. A shared whole-tooltip translation budget reserves
every output character and generated component before allocation (1024 generated
nodes, 512 visited input components), including repeated arguments, literals, fallback
strings and sibling/line traversal. Oversize translation trees stop during expansion. Excessive content explicitly rejects instead of
silently clipping important attributes. Encoded PNGs remain capped at 256 KiB. Tooltip
cache is 32 entries/4 MiB per immutable resource generation. Glyph ink is RGB-modulated
for actual text style/shadow and cached at 128 entries/one million pixels. Its primitive
glyph identities never retain original font sheets; cached ink owns independent rasters. Rendering
checks a two-second work deadline; zero-advance glyphs retain their measured advance. Empty hands create no image.
No extra remote download, client renderer, network source or production environment
fallback is introduced.

## Retained model/resource utilities

The local pack index and independently implemented CPU model resolver remain available
for focused resource verification and future use; `%item` no longer invokes model
rasterization for Discord. Existing model fixtures and original-asset comparisons stay
separate from the current tooltip presentation. There is no remote asset download or
redistribution of game source packs.

The independent CPU renderer supports this bounded standard JSON profile:

- Generated/handheld items, up to five composited layers; parent and texture-variable
  references, including standard generated and cube/block builtin fallbacks.
- Legacy overrides in reverse order, last matching threshold wins. Supported
  predicates: `custom_model_data`, `damage`, `damaged`, `lefthanded`. Unknown runtime
  predicates require fallback instead of guessing a mod/client callback result.
- Up to 64 cuboid elements, six faces each, explicit/default UV in 0..16, 90-degree
  UV rotation, element axis rotation/rescale, inherited `display.gui` transforms,
  front/side lighting, shade flags and known tint index zero. Rasterization uses
  UV sampling, depth and alpha compositing; it does not select an arbitrary first
  texture as a complete model. Lighting/transparency are a software approximation,
  not a pixel-identical Minecraft GPU screenshot.
- Texture animation displays its first explicitly selected metadata frame. Frame
  sizing follows pinned Minecraft 1.20.1 `AnimationMetadataSection.calculateFrameSize`:
  neither axis specified uses the shorter image axis for square frames; width-only
  preserves full image height, height-only preserves full image width, and explicit
  width/height uses both. Horizontal, vertical and rectangular sheets are supported.
  Tests compare these four size cases directly with the pinned native metadata
  class and check actual selected-frame colors. This
  stage produces PNG, not animated item cards. Generated item extrusion, X/Y-rotated
  generated layers, enchantment glint and custom render types/loaders/root transforms,
  BEWLR, GeckoLib, OptiFine CIT and arbitrary Forge client properties are unsupported.
  Unsupported/missing models visibly say `Model unavailable / unsupported`; enchanted
  supported items explicitly say glint is omitted.
- Bitmap, space and reference font providers, including supplementary code points;
  provider/grid/reference limits and measured-width truncation. One root font applies
  to the item name. Mixed sibling fonts, shaping, negative space, TTF/unihex/custom
  providers are outside this profile. Unsupported font definitions visibly report
  `Font fallback`; the bundled renamed OFL CJK font supplies ordinary Chinese/English
  fallback without depending on host fonts or DISPLAY.

The immutable resource snapshot lives behind the renderer instance. Restart builds
a new complete index/renderer before opening transport; failed imports do not publish
a partial replacement. No file watcher or in-place reload is implemented. Rendered
cards cache by the full whitelisted appearance within that resource generation:
32 entries/4 MiB encoded bytes. Texture cache: 16 entries/four million pixels;
per-render references: eight million pixels. Bitmap fonts: 4,096 glyphs, 64 providers,
one million sheet pixels per face, eight cached faces/two million pixels total.
Model JSON cache: 16 entries, bounded by the import JSON limit. Model/texture/font
references have cycle/depth limits. Render work has a two-second/4,096-step budget,
uses the existing single bounded server worker and never executes pack content.

## Evidence and licensing

`ItemRenderTest` uses original synthetic assets and loopback official-REST fixtures
for layers, two CMD thresholds, inherited texture variables, visible 3D cube faces,
UV rotation, selected animation frame, fonts, unsupported/cyclic references, tint,
cache limits, marker count, attachment conflicts, mention offsets and safe literals.
`scripts/verify-server-events.py --items` boots actual Forge without DISPLAY and
captures a real main-hand ItemStack, changes the stack afterward, then verifies its
stable snapshot through localization, bitmap/model rendering and actual adapter
multipart delivery to **loopback only**. It checks empty hand/offhand, imported
Traditional Chinese and absence of private fixture NBT. These are controlled fake
player events, not a claim of human gameplay or live Discord item delivery.

`ItemTooltipAcceptance`, run by `verify-server-events.py --items`, compares the allowed
semantic lines against pinned native 1.20.1 getTooltipLines on real ItemStacks. It covers
ordinary swords, enchanted tools, custom names/lore, HideFlags, empty and filtered-empty
attribute overrides, empty hand, long multilingual wrapping, missing glyphs and exact
production-adapter multipart text/PNG delivery to loopback. `tooltip-enchanted-tool.png`,
`tooltip-traditional-chinese.png`, `tooltip-metadata.json` and `tooltip-result.txt` are
under `build/evidence/server-events/`. Metadata includes dimensions, encoded bytes,
font source and exact safe semantic lines. The current `authoritative-main-hand.png`
is also a tooltip; original model-card fixtures remain only renderer tests. Test source/fixtures are excluded from the mod JAR.
No GPL renderer or addon code/dependency was copied or linked; no project license
was changed. Architecture was informed by the user-supplied InteractiveChat addon
research. Standard model concepts follow the official [Forge model documentation](https://docs.minecraftforge.net/en/1.20.1/resources/client/models/).

### Optional original-asset/native-client comparison

The retained model-renderer tests use synthetic fixtures. A separate local check
now uses legally existing Minecraft 1.20.1 cache resources: diamond sword and stone
with their actual parent-model/texture dependency closure, `en_us` and `zh_tw`.
`prepare-vanilla-item-evidence.py` selects only 13 files from the cached
`client-extra.jar` and indexed language object; it performs no downloads. It checks
the cached game version and language object integrity and records selected-file
hashes in `build/evidence/vanilla-items/provenance.json`. The temporary selected
source ZIP stays in ignored `build/local-vanilla-item-assets`, outside CI artifact
directories. No complete game asset pack is committed, uploaded or distributed.

Run with a cache that already contains the assets:

```sh
python3 scripts/prepare-vanilla-item-evidence.py --gradle-cache <existing-gradle-cache>
python3 scripts/verify-server-events.py --items --vanilla-pack build/local-vanilla-item-assets/vanilla-evidence.zip
xvfb-run -a -s '-screen 0 1280x900x24 -nolisten tcp' python3 scripts/verify-vanilla-item-client.py
```

The dedicated-server check captures real ItemStacks for both items and renders
the original cached models/textures with names `鑽石劍` and `石頭`, without model
fallback. The separate client check opens an isolated test screen, connects to no
server, and compares those cards with the actual Minecraft renderer and native
`zh_tw` names. Its original 1100×800 framebuffer at verified GUI scale two is
`build/evidence/vanilla-items/native-vs-headless-1100x800.png`. Headless PNGs display
at original pixels; native icons are explicitly labelled 4× enlarged. The screenshot
does not claim pixel-identical lighting, full vanilla asset coverage, human gameplay,
modded rendering or live Discord delivery. Vanilla font providers are not imported
in this check: headless Chinese text uses the bundled CJK fallback; native labels use
Minecraft's font. Bitmap-provider support remains independently fixture-tested.

## Short marker alias

`%i` and `%item` mean the same authoritative main-hand snapshot. They may be
mixed and repeated, sharing the total limit of eight active markers and one
Discord tooltip PNG. The common parser uses longest match (`%item` before `%i`)
and UTF-16 spans. `%` starts a token; an immediately following ASCII letter,
digit or underscore prevents the match, so `%inventory`, `%itemized`, `%i_`
and `%i9` stay literal. Chinese adjacency such as `看看%i吧` is supported.
Only lowercase tokens are defined. Selected mention ranges remain literal and
are excluded before the eight-marker expansion limit. Variable token lengths
remap both native and escaped Discord mention offsets. Empty hand, upload/item
conflicts, output limits and safe native hover rules are unchanged. Expansion
never recursively processes the generated item name.
