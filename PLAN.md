# Android Navidrome music player — implementation plan

## 1. Goal and status

Build a native Kotlin/Jetpack Compose Android music player that connects to a
Navidrome server, browses server playlists and music, streams audio, and supports
intentional downloads for reliable offline playback. Support phones and tablets,
including resizable windows, in light and dark themes using the supplied mockups.

This is an implementation plan, not a statement that an app exists. All phases
below start unverified. Read `AGENTS.md`, `flake.nix`, `nix/android.nix`, and any
existing README/Gradle/source files before starting. Preserve working code and
the ARM A14 development environment. Do not copy Bubbles messaging, Rust bridge,
Apple authentication, or validation-service architecture into this project.

The app name and application ID are not specified. Retain existing choices if
present; otherwise use clearly documented provisional values without blocking
implementation. Do not claim product branding has been approved.

### First-release scope

- One active Navidrome server/account, with connection setup and reauthentication.
- Home with server playlists, songs, and albums.
- Search for music, matching the navigation shown in the mockups.
- Library with playlists, songs, albums, artists, and downloaded content.
- Playlist, album, and basic artist detail screens.
- Persistent mini-player on phones; player pane on sufficiently wide windows.
- Full Now Playing, playback queue, seek, previous/next, shuffle, and repeat.
- Background audio, media notification, lock-screen and headset controls.
- Online-first server playlists with metadata and ordered track entries.
- Cached browsing and explicit track/album/playlist downloads for offline use.
- Basic settings, download management, and responsive light/dark UI.

### Deferred scope

Do not implement playlist creation/editing/reordering on the server in the first
release. Server playlists are initially read-only; users manage them in
Navidrome. Local playback queue reordering is included and must not change the
server playlist. Also defer simultaneous multi-account use, casting/remote
playback, podcasts, lyrics, recommendations, social features, artist following,
equalizers, crossfade, Android Auto-specific browsing, and scrobbling.

Mockup buttons for these features are visual references, not requirements to
ship nonfunctional controls. Hide unsupported actions instead of faking them.

## 2. Design reference and screen contract

### Reference inventory

The following ten supplied images were reviewed from mockups/. Place copies in
`mockups/` when available to the implementing agent, retaining these
filenames. Do not assume images are already committed. If unavailable, use the
written specification below and record that visual comparison is pending.

| Filename (`Screenshot From 2026-10-06 … .png`) | Reference |
|---|---|
| `16-24-37` | Dark phone Home, Search, Library; mini-player above bottom navigation. |
| `16-24-46` | Dark phone Now Playing, Album, Artist. |
| `16-24-57` | Light phone Home, Search, Library. |
| `16-25-04` | Light phone Now Playing, Album, Artist. |
| `16-25-12` | Dark tablet Home, left rail and persistent right player pane. |
| `16-25-25` | Dark tablet album artwork/details beside tracks and right player pane. |
| `16-25-31` | Dark tablet expanded Now Playing beside Up Next queue. |
| `16-25-42` | Light tablet Home and persistent right player pane. |
| `16-25-50` | Light tablet album detail and player pane. |
| `16-25-57` | Light tablet expanded Now Playing and queue. |

Use the artwork-led composition, rounded covers and panels, restrained neutral
surfaces, strong title hierarchy, muted secondary text, monochrome playback
buttons, and cover-tinted player backgrounds. Dark surfaces are near-black;
light surfaces are off-white with pale gray cards. Derive optional tint from
artwork with contrast-safe fallbacks. Define shared color, typography, spacing,
and shape tokens rather than scattering magic values through screens.

The screenshots contain fictional music, counts, and names. Use those only as
preview/test inspiration. Real screens must use real server data or truthful
empty states. Do not draw screenshot device bezels, status bars, or the gray
presentation canvas inside the app. Use Android system insets and actual system
bars. Initial design tokens can use an 8dp spacing grid, 16–24dp page padding,
12–24dp corner radii, and at least 48dp interactive targets; tune by comparison.

### Screen requirements

| Screen | Required behavior and content |
|---|---|
| Connect | Server URL, username, password, show/hide password, connect action, progress, and actionable error. Accept reverse-proxy subpaths. Persist a successful account securely. |
| Home | Greeting/header and settings access; compact shortcut tiles; distinct playlist, album, and song sections with Show all where useful. Use real server playlists, recently added albums, and locally recorded recent tracks or another honestly labeled supported collection. |
| Search | Debounced query with cancellation; grouped songs/albums/artists; local filtering of fetched playlists; clear and empty/error states. Offline search is explicitly limited to cached metadata. |
| Library | Filter chips for Playlists, Songs, Albums, Artists, Downloaded; appropriate list/grid layouts, supported sorting, refresh, and clear availability indicators. Do not label arbitrary server contents as user-saved favorites. |
| Playlist detail | Artwork/fallback, title, owner, description/comment when present, track count, duration, ordered tracks, play/shuffle, download, and refresh status. Preserve duplicate song occurrences. |
| Album detail | Artwork-led header, album/artist/year where available, duration/count, play/shuffle/download, ordered tracks with duration and active-row highlight. |
| Artist detail | Name, artwork if supplied, and albums; supported additional metadata only. Do not invent monthly listeners, global play counts, Follow, or Popular rankings. |
| Mini-player | Cover, title, artist, play/pause, progress line; tapping opens Now Playing. Preserve it across main destinations, including settings. Show buffering/error meaningfully. Hide when there is no queue. |
| Now Playing | Large cover, title/artist, source album/playlist, seekbar and elapsed/duration, previous/next, play/pause, shuffle, repeat off/all/one, download action, queue access. No duplicate mini-player on this screen. |
| Queue | Current and upcoming entries, select entry, remove/reorder upcoming entries, clear queue. Duplicate tracks retain separate queue identities. |
| Downloads | Queued/downloading/paused/failed/completed states, progress where known, retry/cancel/remove, used space, and fully/partially downloaded collection status. |
| Settings | Server/account summary, reconnect/sign out, system/light/dark theme, offline-only mode, Wi-Fi-only downloads, storage usage, clear temporary cache, separate remove-downloads action, app version. |

“Made for you” in the mockups becomes “Playlists” unless a real supported
recommendation feature is later implemented. “New releases” becomes “Recently
added albums” when using a newest-added endpoint. Omit camera, microphone,
notification bell, device-casting, sharing, plus/create, lyrics, and related
controls until corresponding functionality exists. A starred collection may be
added using server favorites, but is not required to fabricate “Liked Songs.”

### Adaptive layout rules

Use available window size, not device model or orientation alone. Begin with
compact width below 600dp, medium 600–839dp, expanded 840dp and above; validate
against the actual Compose adaptive APIs selected during setup.

- Compact: Home/Search/Library bottom navigation, mini-player above it, single
  column detail screens, full-screen Now Playing. Settings comes from a header
  action. Downloads is accessible from Library.
- Medium: navigation rail and flexible content; retain mini-player when a side
  pane would make content cramped. Portrait tablets and split-screen must work.
- Expanded: left navigation rail, flexible browsing area, persistent player
  pane roughly 300–360dp wide when space permits. Include Downloads and Settings
  access in the rail. Avoid rendering the phone mini-player at the same time.
- Very wide detail layouts: artwork/metadata beside the track list, with the
  player pane where room remains. Collapse columns before text becomes cramped.
- Expanded Now Playing: large playback area beside Up Next; omit deferred Lyrics
  and Related tabs. On compact windows, queue is a sheet or separate screen.
- Preserve destination, selection, scroll where practical, queue, and playback
  through rotation, window resizing, and pane changes. Never create a second
  player during a layout transition.
- Handle long titles, missing art, font scaling, keyboard visibility, gesture
  insets, landscape, TalkBack, and keyboard focus. Do not force phone portrait.

## 3. Proposed implementation structure

Use Kotlin, Compose Material 3, Navigation Compose, coroutines/Flow, and
lifecycle-aware state collection. Start with one Android app module organized
by responsibility; split modules only when justified. Prefer straightforward
constructor injection and a small app container over premature framework work.

| Boundary | Responsibility |
|---|---|
| `data/remote` | Typed Subsonic/OpenSubsonic client, auth, DTOs, response validation, URL building. |
| `data/local` | Room metadata/cache tables, migrations, download references, playback restore state. |
| `data/repository` | Refresh policies, DTO mapping, cache reads, account scoping, exposed domain flows. |
| `playback` | One Media3 ExoPlayer owned by a MediaSessionService; controller-facing playback state. |
| `downloads` | Media3 download manager/service, persistent audio storage, reconciliation and removal. |
| `settings` | DataStore for non-secret preferences; separate credential storage. |
| `ui` | Navigation, theme/components, screen ViewModels and stateless screen content. |

Select and pin compatible stable dependencies when scaffolding: Media3
ExoPlayer/Session and download-related artifacts, OkHttp with Kotlin serialization
(optionally Retrofit), Room with compatible KSP, DataStore, and Coil for artwork.
Use one compatible version across Media3 artifacts. Record versions in the
project's version catalog. Do not add host FFmpeg, Rust, or a native decoder
extension to the APK merely because this is a music app.

UI observes repository and player state; it does not own sockets, an ExoPlayer,
or a second database. Room is the observable metadata cache. The server remains
authoritative for remote metadata; Media3's download state plus verified stored
bytes determines download completion. Do not maintain competing completion flags
without reconciliation.

## 4. Navidrome connection and API behavior

Implement against Navidrome's documented Subsonic/OpenSubsonic interface under
`/rest`, not scraped web UI or an assumed private API. Consult the current
compatibility list and endpoint specifications during implementation.

- Normalize the server base URL safely, preserving subpaths and encoding query
  parameters. Avoid duplicate `/rest` segments; explain expected URL format.
- Prefer HTTPS. Do not install permissive certificate trust or hostname checks.
  If HTTP is needed, require explicit per-server insecure-connection consent and
  implement/test Android's cleartext policy without silently downgrading HTTPS.
- Support username/password token authentication (`t` and fresh `s`, per the
  protocol) with client/version/JSON parameters. Treat this as protocol
  compatibility, not a substitute for TLS. Negotiate optional extensions rather
  than assuming API-key auth or modern features exist on every server.
- Store a needed reusable secret encrypted using an Android Keystore-protected
  key in app-private storage; exclude it from backups. Do not store it in plain
  DataStore, URLs in the database, logs, or crash reports. A salted token alone
  does not let the client generate tokens for future salts without a strategy.
- Build authenticated requests at use time. Redact auth/query data from network
  diagnostics. Cover art and stream requests also need authentication handling.
- Validate both HTTP status and the Subsonic response envelope: HTTP 200 can
  still contain an API failure. Distinguish auth failure, permission denial,
  unsupported endpoint, missing media, timeout, TLS, and unreachable server.
- On auth failure keep account-scoped offline downloads usable; offer reconnect.
  Do not repeatedly retry invalid credentials or erase local data automatically.
- Retry transient reads with bounded backoff, cancellation, and no main-thread
  blocking. Connectivity callbacks are hints; actual requests determine reachability.

| Need | Initial API candidates; verify server support |
|---|---|
| Validate connection | `ping`; optional `getOpenSubsonicExtensions`. |
| Playlists and ordered entries | `getPlaylists`, `getPlaylist`. |
| Albums and artists | `getAlbumList2`, `getAlbum`, `getArtists`, `getArtist`. |
| Search | `search3` with pagination; filter playlists separately. |
| Individual tracks and artwork | `getSong`, `getCoverArt`. |
| Playback and downloads | `stream`; `download` for original files when permitted. |
| Song favourites (heart) | `star`/`unstar` with `id`; `getStarred2` to load. In first-release scope (user request 2026-10-06): heart on mini-player, Now Playing and player pane; optimistic update, revert on failure. |

Do not assume a universal “all songs” endpoint. Verify Navidrome's supported
paged `search3` empty-query behavior; if necessary build the song index through
paged album lists and album tracks in a bounded background job. Do not block
Home on a complete library crawl. Parse optional/missing fields gracefully and
ignore unknown JSON fields.

Scope every remote ID, cache key, artwork key, and download to a stable local
account/server identity. Different servers can use identical song IDs. Never
merge them. Preserve playlist order and duplicates with occurrence/position
keys, not a unique constraint on song ID alone.

## 5. Online-first playlists and metadata

Online-first means Navidrome defines the playlist and the app refreshes it when
reachable. Cached snapshots support fast rendering and offline access; they do
not become an independently editable local playlist.

- Render cached content promptly, then refresh on entry when stale; always allow
  explicit refresh. Show refreshing, last successful sync, and stale/offline
  state without replacing usable content with a full-screen spinner.
- Persist playlist ID/name/owner/comment/artwork reference, public flag and
  timestamps where supplied, counts/duration, ordered entry occurrences, and
  track metadata required to render and play them. Missing values remain unknown.
- Replace a playlist snapshot transactionally only after a complete successful
  response. Failed or partial fetches must not wipe the last good snapshot.
- A playlist-list response may not contain full tracks: fetch detail on opening
  or downloading. Use bounded concurrency for art/detail fetches.
- Refresh server changes without silently rewriting the current playback queue.
  A queue is a snapshot created by a playback action; later playback can use the
  newer playlist snapshot.
- If a playlist disappears in a successful authoritative refresh, mark it no
  longer available remotely. Preserve explicitly downloaded audio and enough
  local metadata to access it through Downloads until the user removes it.
- First release downloads a playlist snapshot on explicit request. Later server
  changes show “Downloads out of date”; an explicit Update downloads action
  reconciles it.
- Per-playlist opt-in **Keep updated** (user request, 2026-10-06; the user has
  server-side scripts that replace whole playlists daily): when a downloaded
  playlist changes on the server, the app downloads the new songs, commits the
  new membership once they finish, and releases songs no other download keeps.
  It follows the download policy (Wi-Fi/unmetered only by default, never in
  offline-only mode), shows its status and last update on the playlist and in
  Downloads, can be turned off at any time, and reports partial failures
  without claiming completion. Change detection uses the playlist list's
  `changed`/`songCount` (already used to refetch rewritten playlists) and runs
  when the app checks playlists; background scheduling while the app is closed
  (e.g. WorkManager with network constraints) is part of this option.
  Playlists without Keep updated never re-download automatically.

## 6. Playback and offline contract

### Playback

One service-owned Media3 player handles streaming and downloaded media. UI
connects through a MediaController. Supply media metadata/artwork to the media
session and implement required manifest permissions/service types according to
the selected target SDK. Verify background starts and media notifications on an
actual device, not only in previews.

Support ordered play from a selected row, shuffle, repeat, seeking, queue edits,
audio focus, headset unplug handling, Bluetooth controls, and track transitions.
Use stable queue occurrence IDs. Persist enough state to restore a paused queue
and position after process recreation; do not autoplay unexpectedly after reboot
or cold launch. Foreground/background transitions must not interrupt playback.

Prefer a complete downloaded representation when available, otherwise stream
when policy allows. Keep URLs/credentials transient. Validate original MP3,
AAC/M4A, FLAC, and Opus fixtures on the target device; support server transcoding
where needed and available, with clear errors rather than unsupported promises.
Verify seeking and reconnection for both original and transcoded streams.

### Offline storage and policy

Use Media3 DownloadManager/DownloadService with durable download cache storage
and shared download-aware playback data sources. Keep explicit downloads separate
from any size-bounded evictable streaming cache. Clearing temporary cache must
never delete explicit downloads. Store pinned downloads outside OS temporary
cache directories. Use a non-evicting policy for downloaded bytes.

- Use an account + track + representation/version key independent of expiring
  authenticated URLs. Never mix original-file bytes with transcoded bytes under
  the same key. Download and playback URI differences must still resolve the
  same exact downloaded representation.
- Prefer original-file downloads initially when supported and playable. If a
  transcoded representation is required, validate it explicitly, label its
  quality, and treat it as a separate representation.
- Persist metadata, collection membership/order, and required artwork for offline
  browsing. Artwork failure need not fail audio completion; show a fallback.
- Track queued, running, paused, failed, and complete download states. Restart
  recovery must reconcile Media3's index with metadata and actual available data.
- Interrupted or partial data is not “Downloaded.” Resume only when the endpoint
  and representation safely support it; otherwise restart cleanly.
- Support cancellation, retry, Wi-Fi/unmetered policy, disk-full handling, and
  explicit removal. Do not silently consume mobile data after a policy change.
- Deduplicate shared audio across downloaded collections. Removing one playlist's
  download must not remove tracks still retained by another collection or an
  explicit individual-track download. Record retention references.
- Reconcile a playlist download update safely: retain the old snapshot until new
  required downloads finish, then commit the new membership; clean up only
  unreferenced bytes. Report partial failures without claiming full completion.
- Offline-only mode makes no remote browse/art/stream/download requests. Disable
  online actions with an explanation; allow cached browsing and completed audio.
- In automatic mode, remote failures fall back to cached metadata. For a mixed
  queue without connectivity, skip unavailable tracks with visible feedback;
  stop with an explanation if no playable tracks remain. Avoid retry loops.
- Keep a downloaded playlist's order even if only some entries are available;
  label partial download counts. Offline Play queues available entries and
  explains omitted unavailable items.
- Sign-out stops playback/download work and clears credentials. Confirm removal
  of that account's downloads and cached data before destructive sign-out; allow
  cancelling. Reauthentication of the same account must not purge downloads.

Offline acceptance means a cold launch in airplane mode can browse a downloaded
playlist, render its metadata/art or fallback, and play/seek/skip its downloaded
tracks. A song that happens to remain buffered is not evidence of offline support.

## 7. Implementation phases and gates

Work sequentially through working vertical slices. For each phase update its
status and append evidence in section 9. Do not check a box solely because code
was written. When live server/device access is missing, finish fixture-backed
work, document the precise blocked check, and do not claim the integration passed.

### Phase 0 — Repository and toolchain baseline

- [x] Inspect repository and agent guidance; retain working code and pins.
- [x] Ensure the Android Nix support files are present and tracked.
- [x] Scaffold only missing Kotlin/Compose app and Gradle wrapper/configuration.
- [x] Pin compatible dependencies and establish package ID/SDK choices in README.
- [x] Run `nix develop`, `android-doctor`, and `android-build` on the A14.
- [x] Launch a minimal APK on an authorized Android device.

Gate: documented toolchain and a real launchable APK; separately report any checks
performed on x86-64 rather than the A14. No fabricated previous validation.

### Phase 1 — Design system and adaptive shell

- [x] Implement light/dark/system theme, shared cards/rows, navigation and insets.
- [x] Implement compact/medium/expanded scaffolds and placeholder playback state.
- [x] Add deterministic fake repositories for previews and UI tests only.
- [x] Build Home, Library, details, mini-player, full-player and queue compositions.
- [ ] Review phone/tablet screenshots against the ten references.

Gate: coherent navigable layouts at narrow and wide sizes with no clipped controls;
fake data is isolated from production wiring. Controls without behavior are not
presented as finished features.

### Phase 2 — Account, API, and cached metadata

- [x] Implement connect/reconnect, URL handling, token auth, credential storage.
- [x] Add API envelope/error mapping and fixture-driven contract tests.
- [x] Create account-scoped Room schema and transactional repository refreshes.
- [x] Connect to a real Navidrome server; display real playlists and albums.
- [ ] Test wrong credentials, proxy subpath, server outage, and missing metadata.

Gate: successful restart with a stored account, real metadata refresh, cached
fallback, and no secrets in diagnostic output.

### Phase 3 — End-to-end streaming vertical slice

- [x] Implement service-owned ExoPlayer, media session, controller, and queue state.
- [x] Play a real playlist track and move through its ordered queue.
- [x] Wire mini-player/full-player/tablet-pane controls to that same player.
- [x] Add seek/shuffle/repeat and persisted paused queue restoration.
- [x] Validate background/lock-screen/headset controls and network errors.

Gate: real music plays across navigation, rotation, screen lock, and app background;
all player surfaces agree about current track and play state.

### Phase 4 — Complete browsing and online-first playlists

- [x] Complete Home playlist/song/album sections with accurate section labels.
- [x] Complete searchable/paged Library and album/artist/playlist details.
- [x] Implement refresh, empty/error/stale states and duplicate playlist entries.
- [ ] Confirm playlist changes made in Navidrome appear after refresh.
- [ ] Verify current queue remains stable when server playlists refresh.

Gate: realistic large and empty libraries work; no invented recommendations,
metadata, global popularity, or unsupported actions remain in production UI.

### Phase 5 — Downloads and offline operation

- [ ] Implement download manager/service and representation-safe cache keys.
- [ ] Add per-track and collection downloads, progress, retry/cancel/removal.
- [ ] Persist snapshots/artwork/retention references and recover interrupted work.
- [ ] Implement explicit playlist download updates and partial-completion states.
- [ ] Add per-playlist Keep updated (opt-in, policy-respecting, background-capable) with status.
- [ ] Add offline-only mode and automatic unavailable-server fallback.
- [ ] Run cold-start airplane-mode playback, seeking and queue tests.

Gate: offline support works without prior running playback; shared-download removal,
disk-full, partial download, process restart and stale-playlist cases are handled.

### Phase 6 — Settings, adaptive polish and accessibility

- [ ] Complete theme/account/network/storage settings with clear destructive actions.
- [ ] Polish tablet list/detail and expanded Now Playing; medium-width fallback.
- [ ] Test resize/rotation during playback and download progress.
- [ ] Test long text, missing art, large font, TalkBack labels and focus order.
- [ ] Capture final light/dark phone/tablet screenshots and document deviations.

Gate: settings persist; responsive layouts remain usable; no visual-only controls.

### Phase 7 — Release readiness and handoff

- [ ] Run unit tests, lint, assembly, and relevant instrumentation tests.
- [ ] Execute the acceptance matrix below against a real server/device.
- [ ] Record tested Navidrome version, Android versions, hardware and build host.
- [ ] Document setup, supported features, download policy, and known limitations.
- [ ] Remove debug fixtures from production paths and inspect sensitive logging.

Gate: produce an installable debug APK and honest handoff notes. Release signing,
store publication, and distribution are separate tasks; do not upload automatically.

## 8. Acceptance matrix

| Scenario | Expected result |
|---|---|
| Fresh install and connect | Valid URL/account connects; invalid input and auth failures are actionable. |
| Reverse proxy subpath | Metadata, art, stream, and download requests preserve the correct prefix. |
| Empty/large library | Accurate empty state or paged browsing without blocking the main thread. |
| Server playlist edit | Refresh reflects title/metadata/order/duplicates; current queue is unchanged. |
| Daily-rewritten playlist | List refresh notices the change and refetches viewed playlists; with Keep updated, downloads follow on Wi-Fi and old songs are released. |
| Playlist removed remotely | Remote state updates; downloaded music remains accessible until removal. |
| Stream a track and seek | Correct audio/metadata and progress; buffering/errors shown honestly. |
| Background/lock/headset | Playback and controls work through MediaSession with correct notification. |
| Rotation/window resize | No duplicate player, lost queue, navigation reset, or overlapping controls. |
| Restart/process death | Paused queue restores; download state reconciles; no surprise autoplay. |
| Interrupted download | Partial item never reports complete; retry/resume behaves safely. |
| Airplane-mode cold launch | Downloaded collection browses and plays/seeks/skips without network. |
| Offline mixed playlist | Available subset plays; unavailable entries and partial count are visible. |
| Auth expires/server down | Cached data/downloads survive; reconnect offered; retries are bounded. |
| Shared download removal | Other retained collections/individual downloads remain playable. |
| Clear temporary cache | Explicit downloads remain intact. |
| Disk full/Wi-Fi constraint | Clear failure or waiting state; no corrupted completion flag or policy bypass. |
| Account change/sign-out | No cross-account data/media leakage; destructive cleanup is confirmed. |
| Themes and accessibility | Both themes, font scaling and TalkBack work across phone/tablet layouts. |

Use unit tests for URL/auth/error mapping, DTO defaults, duplicate ordering,
transactional refresh, representation keys and retention accounting. Use API
fixtures/MockWebServer for malformed responses, auth errors inside HTTP 200,
timeouts and pagination. Use instrumentation/device tests for service lifecycle,
actual playback, download recovery and UI behavior. Tests must cover behavior,
not simply mirror private implementation details.

Typical project commands, once corresponding tasks exist:

```bash
nix develop
android-doctor
android-gradle testDebugUnitTest lintDebug
android-build
android-run --logcat
android-gradle connectedDebugAndroidTest
```

For sandbox builds use the separate Gradle home documented in `AGENTS.md` and
host ADB for installation unless sandbox device access has been configured.

## 9. Agent progress log

Update this table as work proceeds. Include concrete commands and outcomes,
not only “done.” No implementation or build validation has been performed by
creating this plan.

| Phase | Status | Evidence / host / device | Remaining issues |
|---|---|---|---|
| 0 — Baseline | Done | 2026-10-06, aarch64 A14. Scaffold: Gradle 9.8.0 wrapper, AGP 9.4.1, Kotlin 2.4.20, Compose BOM 2026.09.00, `dev.streamer.app` (provisional), minSdk 36, compile/targetSdk 37. AndroidX requires compileSdk ≥ 37, so `nix/android.nix` moved to platform 37.0, build-tools 37.0.0, cmdline-tools 22.0 and now generates the platform `package.xml` AGP needs for a read-only SDK. Rebuilt shell: AAPT2 2.20-15087165 via QEMU; `android-gradle --no-daemon clean assembleDebug testDebugUnitTest lintDebug` passed in the agent sandbox (lint: 0 issues). User ran `android-build` and `android-run` on the host: APK installed and launched on their phone. | Device model/Android version not recorded. Launcher icon is the system default placeholder. |
| 1 — Adaptive shell | Done (phone) | 2026-10-06, aarch64 A14 sandbox: `android-gradle --no-daemon testDebugUnitTest lintDebug assembleRelease` passed — 14 JVM tests (layout breakpoints/pane fit, formatting, demo queue semantics incl. duplicates, shuffle restore, repeat, queue edits), lint 0 issues. Demo catalogue + silent in-memory player live only in `src/debug`; release APK dex contains no demo strings (checked). Shell: bottom bar + mini-player (<600dp), rail + mini-player (600dp to pane fit), rail + 340dp player pane when ≥480dp content remains; NavHost position is layout-independent. User device review: dark-mode text unreadable (no root Surface → black LocalContentColor), slow 700ms default nav fades, predictive-back scale-down on pop. Fixed: root Surface + explicit content colours, 150ms/90ms fades for all six NavHost transitions incl. predictive pop. Added song favourite (heart) on player surfaces backed by `LibraryRepository.setSongStarred` (demo: in-memory). Now 15 JVM tests, lint 0. Now Playing/queue moved from NavHost destinations to a shell-owned player sheet (saved across rotation) so drag-down shrinks it and reveals the page beneath; release past 20% or a fast fling collapses it into the mini-player (or pane on wide windows), otherwise it springs back. Open/close use the same motion. Collapse: a target-height band anchored on the title row (middle for the queue, top for the tall pane) narrows and drops onto the target while everything above and below it fades out (offscreen DstIn mask); the band morphs into the mini-player/pane via a copy of the card, and the real card is hidden until progress reaches 1. Mini-player now floats over the content (pages scroll beneath it; `LocalFloatingPlayerHeight` pads list ends). SheetMotion unit-tested (18 JVM tests). | Tablet/expanded layouts not yet reviewed on a device (only via `wm size` suggestion); no formal screenshot comparison with mockups. |
| 2 — API and cache | Done | 2026-10-06, aarch64 sandbox: `testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest` pass — 37 JVM tests (URL normalisation incl. sub-path/`/rest` suffix, Subsonic spec token vector, envelope errors inside HTTP 200, HTTP/timeout/malformed mapping, HTTP-consent refusal, DTO defaults and duplicate entries, bounded retry); lint 0. OkHttp 5.5.0, Room 2.8.5 (KSP 2.3.12, schema exported to `app/schemas`), Coil 3.6.3. Account-scoped Room cache with transactional snapshot replacement; Keystore AES-GCM password storage in no-backup dir; per-server explicit HTTP consent (platform cleartext allowed for Tailscale/LAN, app enforces); reauth state stops requests and keeps cache; search falls back to cached metadata; star/unstar optimistic with revert; cover art via credential-free cache keys; image caches cleared on sign-out. Navidrome empty `search3` query returns all songs (checked in Navidrome source); album-crawl fallback kept. Debug still uses the silent demo player with real data. Large playlists: Navidrome `getPlaylist` returns all entries unpaged (checked in source); 2500-entry decode test (JVM) and 2000-entry repository test (device) added. Playlist display sort (default Recently added = reverse playlist order, since Navidrome stores no per-entry added date; also Playlist order/Title/Artist; persisted; playback follows display order). Favourites ordered by server `starred` timestamp (schema v2, Room auto-migration, migration test); Library Favourites filter. Room Gradle plugin manages schemas. User report: their "liked" playlist (all favourites) wasn't newest-first under reverse-position sort — likely a rule-based smart playlist. Added "Recently favourited" sort (per-entry `starred` time), default for playlists whose entries are all favourites; sort now persisted per playlist; starring invalidates cached playlist details. 45 JVM tests. Device: Pixel 11, Android 17 — `connectedDebugAndroidTest` 19/19 passed 2026-10-06 (DAO 7, repository vs MockWebServer 7, migration v1→4, Keystore credential store, MediaItems 3). User connected to their Navidrome 0.60 server over Tailscale and used the library (playlists incl. a large "Liked" smart playlist, favourites, artwork). | Not explicitly exercised by hand: reverse-proxy sub-path, server outage, wrong-password flow on the real server (covered by unit/device tests). Pull-to-refresh/stale indicators are Phase 4. |
| 3 — Streaming | Done (verified on device) | 2026-10-06, aarch64 sandbox: `testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest` pass — 51 JVM tests (incl. QueueShuffleOrder: play-next stays next under shuffle, append last, removal reindexing), lint 0. Media3 1.11.1: `PlaybackService` (MediaSessionService, exported for system/headset/Bluetooth controllers) owns one ExoPlayer with audio focus, becoming-noisy pause, network wake mode; UI uses `MediaPlayerController` (MediaController) for all surfaces. Queue/session carry only `streamer://song/<account>/<id>`; ResolvingDataSource builds the authenticated stream URL per open; notification artwork via `streamer-art://` + Coil-backed BitmapLoader (no tokens exposed). Custom shuffle order keeps current song first and "Play next" next; queue reorder hidden while shuffled. Paused queue + position saved (`queue.json`, no URLs) and restored unprepared on cold start (no autoplay). Local play history (schema v3, auto-migration) feeds Home "Recently played". Demo player/catalogue moved to test sources; no APK ships them. Home shortcut tiles now show the last 4 albums/playlists actually played from (`recent_collection`, schema v4 auto-migration), hidden until something is played. Player/header tint now comes from the cover's main colour (androidx.palette 1.0.0 on the cached 160px cover; dominant unless near-black/white with a prominent vivid swatch), lightness normalised per theme, cached per cover, animated; placeholder colour only when there is no server artwork. Home tab always returns to Home root; reselecting a tab pops to its root. Album pages show the artist picture (cached artist, fetched via getArtist if missing). User reported Phase 3 verified on their device (Pixel 11, Android 17) on 2026-10-06; the individual manual checks were not itemised. | No transcoding option yet; errors pause with a retry message (no auto-skip). |
| 4 — Browsing | Implemented; device + live-server checks pending | 2026-10-06, aarch64 sandbox: `testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest` pass — 55 JVM tests (adds Library sort orders, relative time), lint 0. Repository `syncStatus(target)` (oldest successful refresh across a screen's data + latest error/reauth) and forced `refresh(target)`; pull-to-refresh on Home, Library (per filter), album, playlist and artist pages; "Updated …" on detail pages and "Couldn't update · showing saved music" everywhere without hiding content. Library sort (albums: name/artist/year/recently added via `album.created`, schema v5; artists: name/most albums; playlists: name/recently updated), persisted. Home "Show all" opens the matching Library view. New device test: forced refresh status and failure keeps cached data; migration test now to v5. | User confirmed manual refresh picks up server playlist changes (2026-10-06); queue-unchanged check not reported. Not run yet on device: new device tests. Added since: playlist-list refresh compares `changed`/`songCount` with the cache, marks changed playlists stale and refetches previously viewed ones in the background (device test added). |
| 5 — Offline | Implemented; device + offline validation pending | 2026-10-06, aarch64 sandbox: `testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest` pass — 60 JVM tests (adds DownloadPlanner: first download, keep-old-until-new-complete, release-only, status), lint 0. Media3 DownloadManager + `StreamerDownloadService` (dataSync FGS, progress notification) over a SimpleCache in `filesDir/downloads` with NoOpCacheEvictor; key `orig:<account>:<song>`; downloads use the `download` (original file) endpoint. Playback: `SelectingDataSource` reads the store read-only for songs with a download (upstream = original endpoint, never mixed with streams), otherwise streams. Retention via `download_ref` (owner song/album:/playlist:) and `downloaded_collection` (schema v6); files deleted only when unreferenced; downloaded albums survive server removal. Playlist updates keep old songs until new ones complete; per-playlist Keep updated follows cached contents (debounced) and a WorkManager job (6 h, unmetered by default) refreshes and resumes downloads in the background; one-time resume work when downloads are pending. Wi-Fi-only default (Requirements NETWORK_UNMETERED); offline-only mode (no server requests anywhere, only downloaded songs queued, banner with Go online). Unplayable songs are skipped with a message, stopping after the whole queue fails. Saved covers for downloaded music (`filesDir/artwork`, 160/1000 px) used before the network. UI: download button/status on album and playlist pages, per-song Download/Remove and indicator, Downloads screen (storage, waiting for Wi-Fi, pause/resume, retry, progress, collections incl. removed-from-server, songs), Library Downloaded filter, Settings (Wi-Fi only, offline mode, storage, remove all, clear temporary cache). Sign-out removes downloads. Cancelling: while downloading, menus offer Stop downloading (keep finished songs; collection marked stopped/partly downloaded, excluded from Keep updated until Resume; schema v7) and Cancel and remove; per-song cancel on in-progress rows (collections containing it become partly downloaded). 63 JVM tests. User report: airplane mode looked unchanged (only failed requests revealed it). Added `Connection` (system network callback + server reachability from request outcomes; reset on network change/Retry): banners for no connection / server unreachable / offline mode, freshness lines say why, no refresh attempts without a network, non-downloaded songs dimmed and Play/Shuffle queue only downloaded songs with a note; offline-mode covers read the disk cache with network disabled. Device test: no requests without network. Follow-up user report: still looked online in airplane mode; likely cause: the Tailscale VPN network stays "connected" without underlying networks. Now only non-VPN networks (NOT_VPN + INTERNET) count, and a short-timeout ping (`ServerProbe`) runs on network changes, app resume and Retry; streaming network failures mark the server unreachable. Library: while offline, Downloaded is the first chip and is selected on going offline (restored on reconnect if auto-switched; a chip chosen offline is respected; initial chip and connection decided before the first frame). Removed first-frame pop-ins: download lists and downloaded-song IDs are app-scoped StateFlows; status lines start from the known connection and offline lines omit the time. 64 JVM tests. | User verified on device (2026-10-06): downloaded music plays in airplane mode; offline banners, Library Downloaded-first behaviour and no first-frame pop-ins look right. Not yet reported: airplane-mode cold start, Keep updated overnight, disk-full, new device tests (download refs, album retention, migration v6). Known limitation: an out-of-date downloaded playlist (Keep updated off) shows the server's latest list; songs that left it stay downloaded until Update. |
| 6 — Polish | Not started | — | — |
| 7 — Handoff | Not started | — | — |

## 10. Primary implementation references

Consult these official references and the selected dependency versions while
implementing. API candidates and library choices above are proposed design
choices; actual server compatibility still requires testing.

- [Navidrome Subsonic API compatibility](https://www.navidrome.org/docs/developers/subsonic-api/)
- [OpenSubsonic API documentation](https://opensubsonic.netlify.app/docs/)
- [Playlist detail endpoint](https://opensubsonic.netlify.app/docs/endpoints/getplaylist/)
- [Media3 background playback](https://developer.android.com/media/media3/session/background-playback)
- [Media3 downloading media](https://developer.android.com/media/media3/exoplayer/downloading-media)
- [Compose window size classes](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes)
