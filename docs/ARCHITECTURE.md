# Architecture and trust boundary

`core/` is Java 17 with no Minecraft, loader or third-party dependencies. `Message` carries UUID event/correlation IDs, a CLIENT/RELAY origin, author, content segments and validated attachments. `Content` preserves Unicode and explicitly models custom emoji ID/name/animation/media keys. `Attachment` sniffs/decodes media before it may enter a message. `MediaDecoder` produces bounded ARGB canvases and per-frame timing; partial GIF frames are composited with restore-to-previous and background-palette disposal. GIF playback loops for preview even when a file's source loop count is finite. The static preview supports PNG/JPEG and GIF; it does not support WebP or APNG animation.

`Transport` defines send/poll. `MockTransport` delivers bundled fixtures, queues an outbound-correlation echo to exercise suppression, then emits a peer reply with a new event and correlation. The reply has the explicit author “Mock peer (not Discord)”. It never opens a socket. `ChatSession` is synchronized, enforces local permissions and send pacing, suppresses repeated IDs and outbound correlations, and bounds retained state. A failed send conservatively retains its correlation and consumes its token because transport delivery might be uncertain. This preview has no retry/delivery acknowledgment state machine or durable replay cache.

`forge/` contains the sole loader adapter. Dist.CLIENT subscribers register F8/title-screen entry. The screen uses a single daemon worker to decode/validate media and execute the local transport, then hands UI state back to the Minecraft thread. It scrolls messages, displays Unicode/aliases and custom emoji images, renders still/animated media, and releases texture resources when no longer visible or when the screen closes. It uploads one current frame per visible asset; it does not allocate every GIF frame on the GPU. The common Java2D/ImageIO types in the media layer mean future loaders still need Java desktop support or a replacement media adapter; they do not require Forge imports. No Fabric/NeoForge implementation is started.

## Enforced local policy

| Resource or action | Preview limit |
| --- | --- |
| Text | 2,000 Unicode code points, reject invalid surrogate sequences |
| Attachments | Up to four per message |
| Encoded media | 4 MiB per file; bounded stream read before decode |
| Canvas/frame dimensions | At most 1,024 × 1,024; bounds checked before pixel decode |
| GIF count | At most 60 frames, counted before decoding frames |
| Decoded media pixels | At most 8,388,608 pixels across all frames of one asset |
| Whole-message pixels | At most 8,388,608 across attachments |
| GIF timing | Clamp each delay to 20–10,000 ms |
| Outbound pacing | Five-message burst; refill one message/second using monotonic clock |
| Replay/echo cache | Last 2,048 IDs and correlations; bounded session-only window |
| Transcript | At most 100 messages and 16,777,216 decoded attachment pixels |
| File entry | One regular filename inside `discraft-uploads`, no symlinks or paths |

Permissions independently gate text send, media send and receipt. This is useful local policy, **not server authorization**. A modified client can bypass any local check. Mock `Origin.RELAY` is an in-process label, not authentication. Real ingress must validate identities and deserialize only bounded records. Attachment bytes are defensively copied; decoded frame objects are shared within the trusted process and are not a serialization format.

## Future trusted relay

A Discord Bot token must reside only in a trusted server/relay secret store or environment. The client mod, common model, JAR/resources, logs and repository must never contain it. The client talks to an authenticated relay through a separately designed transport, not directly to Discord. A short-lived, channel-scoped client authorization is separate from a Bot token. No token configuration field, Discord SDK, OAuth flow, HTTP implementation or arbitrary remote media URL is included in this preview.

Before live integration, the relay must implement and test:

- Server-side user/channel authorization and Discord View Channel / Send Messages / Read Message History / Attach Files / external-emoji permissions as applicable. Do not request Administrator. Required intents and approval depend on the eventual bot routing strategy; no grant is presumed.
- Discord REST/Gateway limits, explicit allowed-mentions policy (default no mentions), per-user/channel budgets, bounded queues, backpressure and 429 `retry_after` handling. Local preview pacing is not a Discord rate-limit implementation.
- Durable event IDs, bridge identity and source stamps. Do not forward the bot's own or already-bridged events. Preserve correlations end-to-end; do not trust a client-supplied RELAY origin. Current session-only dedup is intentionally finite.
- Streamed byte caps, MIME/magic validation, pixel/frame/aggregate budgets and resource/time limits **on the relay and again on the client**. Host allowlists and redirect validation must prevent SSRF; attachments should resolve to relay-controlled opaque IDs, not client-selected fetch URLs. Current client performs no URL fetches.
- Sanitized channel/display metadata, escaped literal text, explicit error/delivery states, reconnect/resume and dedup behavior. No messages should execute Minecraft commands or open links automatically.

Minimum live-integration prerequisites, to stop and request separately: a user-authorized trusted relay deployment target; a real Discord application/bot configured by the user; a least-privilege channel installation and any needed intents/grants; approved Discord API/Gateway/CDN and relay network domains. None is needed for this preview. Do not create or use credentials merely to unblock the Forge dependency build.
