# Native UI text rendering

The original controls used Minecraft's default font directly. Runtime glyph probes on Forge 1.20.1 show an 8×8 bitmap provider for Latin letters and a 16×16 Unihex bitmap with oversample 2 for Traditional Chinese. At GUI scale 1 the latter occupies about eight framebuffer pixels vertically. The controls are drawn directly into the game's framebuffer at integer GUI positions; there was no reduced-resolution panel image to upscale. Decorative emoji glyphs have their own existing scale and are not the text path.

`UiText` scopes a vector font to DisCraft's picker labels, search field, footer/emoji names, staged-file labels and tooltips. It does not replace the world's font, ordinary chat, item text or the entire UI's GUI scale. The panel, cell geometry, original smiley artwork and control hit areas stay in place. The search widget is now 20 pixels high, matching its existing 20-pixel input hit area, and uses the exact same Font instance for drawing, caret positions, selection and width clipping. Long labels show an ellipsis and the staged filename can be inspected in its tooltip.

The bundled, renamed **DisCraft UI TC** subset is derived from Noto Sans CJK TC Regular 2.004 under SIL OFL 1.1. It includes 29,769 mapped characters across Latin, combining marks, common CJK/Traditional Chinese, Bopomofo and compatibility/full-width characters. Unsupported characters retain Minecraft's fallback. It adds about 8.24 MB before JAR compression; it requires no OS font installation, remote runtime download or AWT renderer. Attribution, source digest and a reproducible FontTools build script are in `vendor/ui-font/` and `scripts/build-ui-font.py`. FontTools is only a regeneration dependency, not a build/game dependency.

Minecraft's native TrueType/OTF provider rasterizes the outline font at oversample 4. Measured CJK glyph atlases are roughly 39–45×42–44 pixels, yielding about 11 pixels of visible glyph height at GUI scale 1. Provider baseline shift, the scoped draw offset and measured tooltip line height account for Minecraft's legacy baseline. No screenshot resampling or whole-panel transform is used. Increasing glyph information still costs physical pixels; this change uses the existing control spacing for a modest text-only increase, rather than claiming resolution independent of the display.

`UiText.font()` delegates lookups back to Minecraft's existing font manager, including after resource reload, rather than retaining a closed FontSet. Minecraft owns glyph-atlas allocation and cleanup. Only the dedicated `discraft:ui` resource is used; ordinary game/resource-pack fonts are not overwritten. Like other resource-pack UI changes, a user replacement of this resource can change its metrics; arbitrary third-party font packs are not claimed tested.

## Actual evidence and checks

`UiTextAcceptance` runs against an isolated loopback server with the labelled fixture adapter; it does not use Discord credentials or contact Discord. It captures original framebuffers for 1100×800 at GUI scales 1 and 2, 960×640 at scale 1, and 1280×900 at scale 3. It verifies the actual applied GUI scale after resizing, CJK/Latin provider/raster/line bounds, mixed-language search and left/middle/end caret positions, viewport bounds, tab/filter click areas and resource reload. The latest run passed 85 checks.

The delivered before/after comparisons use the same 1100×800 viewport at GUI scales 1 and 2 with the same local fixtures. The earlier baseline driver did not verify the applied scale for its final resize case, so its historical file labelled `before-1280x900-gui3` is excluded from comparisons. The corrected driver explicitly checks actual scale for all after captures. Original PNGs and glyph probes are under `build/evidence/ui-text/`; no enlarged crops are presented as original-resolution evidence.

```bash
# Select Java 17 first. On Linux with Xvfb:
xvfb-run -a -s '-screen 0 1800x1200x24 -nolisten tcp' \
  python3 scripts/verify-ui-text.py --tag after
```

The multiplayer CI job runs this acceptance after the normal two-client checks and uploads both evidence directories. Existing core, protocol, catalog/thumbnail, original mock-client and native gameplay checks remain in place. Windows/macOS rendering and unusual font/resource packs have not been verified.
