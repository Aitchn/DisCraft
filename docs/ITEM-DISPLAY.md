# Headless item presentation — first supported profile

Set `itemDisplay=true` in the private server config to expand `%item` in DisCraft
native chat. It defaults off for existing installations. The server copies the
authenticated sender's **main hand on the game thread** when upload finishes;
the offhand and client-supplied text cannot select another stack. Workers receive
only an immutable appearance whitelist: registry ID, localized/custom name, root
font ID, count, CustomModelData, damage fraction/flag, handedness, known leather or
potion tint and enchanted flag. No full NBT, lore, book pages, container contents,
attributes or hover/click payloads are transmitted or retained in the snapshot.

Up to eight markers outside selected mention labels expand to `[name ×count]` and
share one 288×256 PNG. Empty hand expands to `[Empty hand]` without a fabricated
image. A marker within a selected actor label stays literal. Combining an item
display with an uploaded attachment is rejected; expansion over the existing text
budget is rejected. Native game text keeps the actual name; only generated Discord
literals escape Markdown, neutralize `@` and prevent emoji-alias substitution.
Existing explicitly selected mentions keep their exact IDs with separately remapped
offsets. User message formatting and the `<Aitchn> text` prefix remain unchanged.

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
- Texture animation displays its first explicitly selected metadata frame. This
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
