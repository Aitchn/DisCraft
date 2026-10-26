# Headless item presentation — first supported profile

Set `itemDisplay=true` in the private server config to expand `%item` in DisCraft
native chat. It defaults off for existing installations. The server copies the
authenticated sender's **main hand on the game thread** when upload finishes;
the offhand and client-supplied text cannot select another stack. Workers receive
only an immutable appearance whitelist: registry ID, localized/custom name, root
font ID, count, CustomModelData, damage fraction/flag, handedness, known leather or
potion tint and enchanted flag. A separate native-only tooltip DTO contains an
allowlist of safe item detail. No original stack/NBT, book pages, container contents
or arbitrary hover/click actions are retained or transmitted.

Up to eight markers outside selected mention labels expand to `[name ×count]` and
share one 288×256 PNG **on Discord**. Minecraft displays a styled inline name with
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

## Resource data and rendering

Use the ordered local [resource-pack configuration](RESOURCE-PACKS.md). There is
**no remote asset download or redistribution of Minecraft assets**. Dedicated
servers generally lack client models/textures: operators must supply the relevant
models, textures and parent dependencies in local presentation packs. A pack that
only changes a texture is not a complete vanilla asset source. Missing assets
produce a clearly labelled question-mark card while preserving the actual name.

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

The original fixture card is `build/evidence/server-events/authoritative-main-hand.png`;
its intentionally simple green model and orange bitmap glyphs prove pack overrides,
not vanilla artwork fidelity. Test source/fixtures are excluded from the mod JAR.
No GPL renderer or addon code/dependency was copied or linked; no project license
was changed. Architecture was informed by the user-supplied InteractiveChat addon
research. Standard model concepts follow the official [Forge model documentation](https://docs.minecraftforge.net/en/1.20.1/resources/client/models/).

### Optional original-asset/native-client comparison

The original green TEST card remains a synthetic fixture. A separate local check
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
