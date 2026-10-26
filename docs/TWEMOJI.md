# Built-in Unicode emoji

DisCraft bundles the Twemoji 17.0.3 artwork family, pinned to upstream commit
`b6b55fef1e8636b540a6d016a4729ca8cdf2e60b`. This is the same artwork family
listed by Discord, not an assertion that Discord deploys this exact release.
No runtime CDN, Discord private endpoint, API key or external emoji service is used.

The 3,953 byte-identical 72×72 PNGs occupy 4,206,786 bytes. A pinned Unicode 17.0
index has 5,018 explicit sequence mappings and 3,953 picker entries. Matching
uses code points, longest sequence matches and emoji cluster boundaries: skin
tones, ZWJ families/professions, regional/tag flags, keycaps and qualification
selectors stay together. VS15 requests text; default text-presentation symbols
need the indexed emoji form (usually VS16). Unknown clusters remain text rather
than partially displaying a misleading emoji. No input normalization or global
FE0F removal occurs; stored/transmitted Unicode and mention offsets stay intact.
A scoped zero-advance selector font prevents VS15 from producing a missing box
in native chat without replacing the normal text font.

The native picker, local preview and native chat images use the same checksum-
verified assets. Ordinary player chat is visually decorated on the client using
Forge's player-chat UI event; all non-emoji text styles, click/hover metadata,
raw packets and signatures remain unchanged. Native reflow retains original
components and preserves GuiMessage timestamps/signatures/tags. This does not
route ordinary chat around server moderation. Players without DisCraft retain
plain Unicode/native components. The guild catalog remains separate and keeps
its own availability/role/animated rules.

All picker names are the pinned Unicode English short names; English names and
complete Unicode sequences are searchable across categories. Search/input keeps
its original Unicode string. There is no claim of matching a Discord account's
personalized frequently-used list or exact emoji release.

The decoded asset LRU holds at most 96 independent 72×72 rasters (497,664 pixels).
Native retained relay/player chat owns at most another 96 assets; only visible
native/picker cells upload textures and offscreen textures are released. Native
player display ownership is limited to 12 messages and validated at 512 input
components/depth 32/8,192 literal characters; originals are copied before visual
transformation. Extra unique assets over the retained budget safely stay text.
No atlas backing sheet is retained through glyph cache keys.

GIF provider integration is not enabled. Production GIF UI offers no results;
explicit TEST mode may still show labelled local fixture media. No Tenor, KLIPY,
private Discord GIF endpoint or account/key setup is performed.

## Provenance and reproduction

`core/src/main/resources/twemoji/` includes NOTICE, artwork CC BY 4.0, upstream
MIT and Unicode License v3 texts. Artwork attribution: Twitter, Inc. and other
contributors; maintained by Jason Sofonia, Justine De Caires and Twemoji
contributors. PNG bytes are unmodified; DisCraft indexes/selects and draws them
at interface sizes. This does not change DisCraft's own source license.

Source archives/data and their SHA-256 values are recorded in `manifest.json`.
`scripts/vendor-twemoji.py` consumes already downloaded official inputs under
`build/vendor-input/`, verifies exact pins and reproduces the resources. It does
not download or execute upstream JavaScript. Ordinary builds need no network
access for these assets. Core tests verify/decode every PNG and test sequence,
text fallback, cache ownership and pixel bounds. `scripts/verify-twemoji.py`
uses an isolated actual Forge server/client to check raw wire Unicode, native
and picker rendering, selection, guild coexistence, unconfigured GIF state,
GUI scales 2/3, resource reload and disconnect cleanup. Screenshots are original
1100×800 / 1280×900 framebuffers and explicitly local TEST evidence.
