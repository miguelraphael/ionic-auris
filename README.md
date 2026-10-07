# Auris

All-in-one anime streaming, manga reading and music listening for Android. Native Kotlin +
Jetpack Compose, local-first: **Room is the only thing the UI ever reads from.**

---

## Design rules encoded in this codebase

| Rule | Where it lives |
|---|---|
| Local-first; Room is the single source of truth | `data/local/*` — every read is a `Flow`, no network result ever reaches the UI |
| Writes hit Room immediately, then sync in background | `data/repository/LibraryRepository` + `data/sync/*` |
| Dynamic theming from active artwork | `ui/theme/ArtworkPalette`, `PaletteExtraction`, `ArtworkGradientBackground` |
| Prominent rounded corners everywhere | `ui/theme/Shape.kt` — one `28dp` artwork radius across the app |
| Floating pill bottom navigation | `ui/components/FloatingNavigationBar.kt` |
| Player: centred square art, progress, centred metadata, two control tiers, **no bitrate badge** | `ui/player/MusicPlayerScreen.kt` |

## Reference-derived measurements

Taken from `UI/music-player-UI.jpg` (1080×2400 px, ~2.625× density → 411×914 dp):

| Element | Pixels | dp |
|---|---|---|
| Album art bounds | x 54–1025, y 161–1132 | 370 dp square, 20.5 dp side inset |
| Art corner radius | ~80 | 32 dp |
| Seek track height | 21 | 8 |
| Seek track horizontal extent | x 81–998 | 31 dp insets |
| Nav pill bounds | x 117–962, y 2196–2327 | 322 × 50 dp |
| Nav pill bottom inset | 73 | 28 dp |
| Colours | bg `#161B24`, pill `#2C303C`, track `#252A2E`, fill `#86B4FF` | |

Logo palette (`Logos/`): `#0A84FF` → `#7B2FD4` → `#E8305A` → `#F07818`.

## Module layout

Five Gradle modules:

| Module | Role |
|---|---|
| `:app` | The application |
| `:source-api` | Vendored `eu.kanade.tachiyomi.*` compatibility layer (Anikku's shape) |
| `:source-api-conformance` | Compile-time guard: asserts `:source-api` still matches Anikku |
| `:mock-extension` | Test-only extension APK that proves the loading path |


```
app/src/main/java/com/auris/app/
├── AurisApplication.kt            Application + container wiring
├── AppContainer.kt                Manual DI (no Hilt: the graph is small and singleton-only)
├── MainActivity.kt                Single activity, edge-to-edge
├── core/model/                    MediaType, EntryStatus, LibraryCategory, SyncState
├── data/
│   ├── local/                     Room database, DAOs, type converters
│   │   ├── AurisDatabase.kt
│   │   ├── LibraryDao.kt          Unified favourites: anime + manga + music
│   │   ├── SyncDao.kt             Outbox + auth session
│   │   └── Converters.kt
│   ├── local/entity/
│   │   ├── LibraryEntities.kt     library_entries + library_units
│   │   └── SyncEntities.kt        sync_outbox + sync_sessions
│   ├── remote/                    Supabase (PostgREST) transport + row mapping
│   ├── repository/                Write-through + enqueue pattern, extensions
│   └── sync/                      SyncScheduler, LibrarySyncWorker
├── source/                        Extension manager
│   ├── ExtensionLoader.kt         DexClassLoader + factory discovery
│   ├── DexFileReader.kt           dex class enumeration
│   ├── ExtensionApkReader.kt      Identity from the APK, not the repo index
│   ├── AnimeSourceAdapter.kt      AnimeSource -> MediaSource (one adapter; see below)
│   ├── ExtensionSource.kt         LoadedSource: source + owning package
│   └── repo/                      Index parsing + download client
│       ├── RemoteExtension.kt     index.min.json models, ExtensionRepo URL handling
│       └── ExtensionRepositoryClient.kt
└── ui/
    ├── AurisApp.kt                Shell: NavHost + floating pill overlay
    ├── navigation/                Destinations, AurisNavHost
    ├── theme/                     Colour, Shape, Type, Palette extraction, gradient
    ├── components/                FloatingNavigationBar, AurisSlider, MediaCards
    ├── home/ library/ browse/     Screens
    ├── extensions/                ExtensionsScreen + ExtensionsViewModel
    └── player/                    MusicPlayerScreen + PlayerUiState
```

## The unified favourites table

`library_entries` serves all three pillars. Identity is `(media_type, source_id, source_key)`
— a unique index, so the same title from two Aniyomi sources stays two rows.

```
MUSIC  source_id = "innertube", source_key = YouTube video id
ANIME  source_id = Aniyomi source package, source_key = source variant key
MANGA  same as anime
```

`kitsu_id` links an entry to Kitsu for metadata and tracking; `remote_id` is the UUID
mirrored into Supabase, generated on first insert and never changed.

Episodes/chapters/tracks live in `library_units` under a composite `(entry_id, unit_index)`
primary key with `ON DELETE CASCADE`.

## Sync model

Outbox pattern, written in the same transaction as the change that caused it:

1. Repository writes the row **and** a `sync_outbox` entry → Room.
2. UI observes Room and recomposes. Nothing waits on the network.
3. `SyncScheduler.requestSync()` enqueues a network-constrained one-shot worker.
4. `LibrarySyncWorker` drains the outbox, then pulls remote changes by `updated_at` high-water mark.
5. Transient failures (5xx, offline) → `retry()`. Permanent ones (4xx) → row quarantined as `CONFLICT`.

Progress writes deliberately skip step 3: scrubbing would otherwise wake a worker per frame.
They dedupe on `(entity, op)` and ride the 30-minute periodic pass.

## Version control

`local.properties` holds the Supabase URL and anon key and is ignored (see `.gitignore`). Copy
`local.properties.example` and fill it in after cloning. `.toolchain/` — a local JDK, Gradle
distribution, dependency cache and AVD — is ignored too; it exists so builds are reproducible
without touching system packages.

Room's exported schemas under `app/schemas/` are deliberately **committed**:
`MigrationTestHelper` reads them from the test APK's assets, and a migration cannot be validated
without the historical schema it migrated from.

## Building

```bash
cp local.properties.example local.properties   # add SUPABASE_URL / SUPABASE_ANON_KEY
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest               # Room DAO tests (Robolectric, no device)
./gradlew :app:connectedDebugAndroidTest       # search tests (needs a device/emulator)
```

`SUPABASE_URL` / `SUPABASE_ANON_KEY` are injected as `BuildConfig` fields; only the anon
key belongs in the app. Never ship a service-role key.

## Extension loading

`:source-api` is a **compatibility layer**, not a copy of Aniyomi's library — Aniyomi's
`extensions-lib` is not published to any public Maven repo, so the `eu.kanade.tachiyomi.*`
classes extensions compile against are vendored here to the exact signatures.

The target is **Anikku** (`komikku-app/anikku`, Apache-2.0), not Aniyomi. An audit against
Anikku `master` found the two are *not* API-compatible, and the differences are structural
rather than cosmetic:

| Anikku fact | Consequence for the host |
|---|---|
| `SManga`, `SChapter`, `HttpSource`, `CatalogueSource` are all `typealias`es of their anime counterparts | **Manga is anime.** There is one hierarchy; a source is not typed anime-or-manga |
| Catalogue methods are RxJava 1 `fetchPopularAnime` etc., with `getPopularAnime = fetch....awaitSingle()` | Host must ship RxJava 1.3.8 and provide `awaitSingle` |
| `getAnimeEpisodeUpdate` was **removed**, replaced by abstract `getSeasonList` | Declaring the old method abstract makes every real extension throw `AbstractMethodError` |
| `SEpisode` has `chapter_number` (an alias property), `fillermark`, `summary`, `preview_url`; **no `video`** | Manga-shaped sources assign `chapter_number`, so a real setter must exist |
| `SAnime` carries `originalTitle`/`originalAuthor`/... twins | Sources read `originalTitle` while parsing; losing it yields blank titles |
| Extensions build against OkHttp 5.x | OkHttp 4 -> 5 is not drop-in |

Because manga is anime, there is **one** `AnimeSourceAdapter` and no sealed union. A `MediaType`
argument records how Auris should *present* a source — a caller decision, not something inferred
from the class. An earlier `LoadedAnimeSource`/`LoadedMangaSource` union would have misclassified
every extension, because none of them implements a detectable `MangaSource`.

### The conformance guard

`:source-api-conformance` exists because **every other test is blind to drift.** `:mock-extension`
compiles against `:source-api`, so it proves the layer is self-consistent — if `chapter_number`
disappeared, or `getAnimeEpisodeUpdate` were wrongly still abstract, all 79 tests would keep
passing while real extensions failed to load with `NoSuchMethodError` at runtime.

That module asserts the exact signatures extensions link against, and is **not executed** —
a green build is the signal, a red build means an Anikku extension would not link. Verified by
removing `chapter_number` and watching it fail with `Unresolved reference`; it caught several
real errors on its first run (`getBaseUrl()` vs `baseUrl`, suspend-context violations).

It is an `android.library` rather than a JVM module because Gradle will not resolve a JVM
project against an Android one, and matching the module type guarantees the assertions see the
same declarations extensions do.

Two things follow from that, and both are load-bearing:

- **`DexClassLoader` parent-first delegation** is the mechanism. The extension APK contains
  only its own classes; `eu.kanade.tachiyomi.*` resolves from the host, so an extension's
  `SAnime` and the host's `SAnime` are literally the same `Class`.
- **`:mock-extension` depends on `:source-api` as `compileOnly`**, not `implementation`. A
  real extension ships no API classes, and neither does ours — otherwise the test would pass
  while proving nothing.

| Piece | Role |
|---|---|
| `ExtensionLoader` | Loads APKs, reflects factories, isolates per-extension failures |
| `DexFileReader` | Enumerates classes from dex (no public API exists for this) |
| `ExtensionApkReader` | Reads package/version/signature from the APK itself |
| `LoadedSource` | A loaded source plus its owning package |
| `AnimeSourceAdapter` | Maps `AnimeSource` -> Auris's `MediaSource` |
| `ExtensionsViewModel` | Repo index + Room join; the single writer for install/uninstall/toggle |

Source ids use Aniyomi's exact algorithm — MD5 of `"name/lang/versionId"`, first 8 bytes
big-endian, sign bit cleared — because these values key `library_entries.source_key`; a
different derivation would fork every user's library.

### Extension repository

The default repo is `https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json`
(redirects to `mojuru/anime-repo`, 251 anime extensions). It is **configurable at runtime** in
Settings rather than baked in — these repos move, get taken down, or get replaced, and a
build-time constant would strand every user when that happens. (Anikku's own repo is
DMCA-blocked as of 2026-02-05.)

APK and icon URLs are both derived from a single `ExtensionRepo.directoryUrl`, so pasting either
`https://x.test/repo` or the full `.../repo/index.min.json` resolves identically. That was a
real bug: the two URL builders initially disagreed, and one form produced
`.../index.min.json/apk/x.apk`.

Installs are **local-first**: installed state always comes from Room, never the index, so the
list stays correct offline and "installed but repo unreachable" is not an error state. An APK
lands on a `.part` name first and is only moved into place after its manifest parses — a
half-written file would otherwise be picked up next launch and look like a corrupt extension.
Identity is read from the APK, not the index, since an index could claim one package and ship
another.

**Signature verification is not implemented.** Anikku authenticates the index with the repo's
Ed25519 key and rejects APKs whose signature does not match; this client verifies nothing and
treats the repo URL as user-supplied and trusted. The screen states this in the open rather
than implying the extensions are safe.

### Quirks handled

| Quirk | Why it matters |
|---|---|
| Writable dex is rejected | `SecurityException` unless the APK is `setReadOnly()` |
| dex is little-endian | `RandomAccessFile.readInt()` is big-endian and silently yields `0x70000000` |
| RxJava 1 wraps checked exceptions | A network `IOException` arrives as a bare `RuntimeException`; `awaitSingle` unwraps it so callers can tell "failed" from "empty" |
| Relative artwork URLs | Sources using `attr("src")` return `/cover/x.jpg`, which Coil cannot load |
| Fractional episode numbers | Premieres number 12.5; `ceil` maps them onto whole units |
| Repo version names | `14.15` is neither lexically nor numerically comparable — updates compare `versionCode` |

## Kitsu metadata client

Kitsu integration is three layers: a client, an identifier, and the media detail screen that
renders the result.

Extensions give us a title scraped from a fan site — romanised, Japanese, or a short form.
Kitsu supplies the authoritative metadata: clean titles per locale, a synopsis, and artwork
sized for the UI. The client queries `/api/edge` and normalises the JSON:API into
`KitsuMetadata`.

### What the wire format actually does

Every one of these was verified against live responses, not read from the specification:

| Kitsu behaviour | Why it matters |
|---|---|
| `data` is an **object** for `/anime/{id}` and an **array** for `/anime?...` | A `List<ResourceObject>` declaration parses one and throws on the other |
| Ids are **strings** (`"id": "46474"`) but are 64-bit | Parsed per-resource so one malformed id skips a record, not a page |
| Error bodies carry `errors` and **omit `data` entirely** | A non-null `data` fails the parse with `MissingFieldException`, losing the message it was meant to surface |
| `links` and `meta` are absent, not null, on some responses | Both are nullable with defaults |
| `relationships.genres` has `links` but **no `data`** unless requested; `include=genres` returned an empty array | Genres come from `/genres`, not from a title lookup |
| `titles` keys vary per record (Frieren has 8 locales, Berserk 4) | Callers must never index a fixed locale; `KitsuTitles.best` applies a preference chain |
| `meta.count` is the **total across all pages**, not the page length | A genre filter returned 22,494 on a page of one |
| `page[limit]` silently caps at 20 unauthenticated | Clamped client-side; asking for more would *look* like "no more results" |
| `filter[genres]` takes **ids, not names** | Names return 0 results with no error |
| `sort` takes the attribute name, not a `-` prefixed direction | `sort=popularityRank`, not `-popularityRank` |

The `Accept: application/vnd.api+json` header is mandatory — without it Kitsu returns HTML and
the failure surfaces as a confusing serialization error.

### Matching

`filter[text]` is a loose substring match, so it fails in two directions: unrelated titles
containing the query (`frieren` matches several), and correct titles spelled differently from
the fan site (`Fate/Zero` vs `Fate Zero`). Neither failure is reported by the API.
`KitsuMetadataMatcher` ranks candidates with an explainable score — exact > artifact-stripped >
substring > leading token, plus a **capped** popularity bonus.

The cap is the point: a popular unrelated title must never outrank a correct match of an obscure
one. Live check, searching `Sousou no Frieren`: exact match 113, `Sousou no Frieren 2nd
Season` 73, `Sousou no Frieren: Marumaru no Mahou` 71.

Two bugs the tests caught here: an empty needle scores `SCORE_CONTAINS` against *everything*
(`String.contains("")` is true), and one- and two-character queries are substrings of nearly any
title. Both now require a minimum query length; exact matches are still allowed at any length.

### Identification

`KitsuIdentifier` turns an extension title into a stored `kitsuId`. Three decisions shape it:

- **Not part of `upsert`.** A Kitsu lookup is a network call; folding it into the library write
  would put a round-trip on every save, including offline ones, and turn a Kitsu outage into a
  save failure.
- **Failures are silent.** A failed lookup leaves `kitsuId` null and the entry untouched.
  Metadata is enrichment — losing the extension's own title because Kitsu was unreachable would
  be much worse than having no enrichment.
- **Writes are additive.** `title`, `description` and `status` are never overwritten; only
  `kitsuCoverUrl`, `kitsuOriginalTitle`, `kitsuRating` are added, and `artworkUrl` is replaced
  only when Kitsu supplies a poster. So identification can never make an entry *worse*.

Re-identification prefers a stored id over a text search, because an id is exact while a search
could now match a sequel or a remake. Searches are memoised per query, including *failed* ones,
so a Kitsu outage does not turn into a retry loop against a service already struggling.

Two seams exist for testability without a mocking framework: `KitsuDataSource` (the three calls
the identifier makes) and the write function. The identifier also takes a `log` sink, because
`android.util.Log` throws in plain JVM tests — and these failures are silent by design, so the
messages are worth asserting on.

### The id plumbing bug worth knowing about

`KitsuPage` originally returned bare attributes. But in JSON:API, `id` is a *sibling* of
`attributes`, so decoding straight to an attributes type throws the id away — and the id is the
one value that must be stored. The identifier then synthesised one from the array index, which
would have persisted `kitsuId = "0"` for every anime in the app. `KitsuPage` now carries
`KitsuResource(id, attributes)`, which makes the mistake impossible to express.
`storesKitsuRealIdRatherThanAPositionalIndex` is the regression test.

## Media detail screen

Kitsu metadata up top, extension units below. Two load states are tracked separately — Kitsu and
the extension answer independently, so a Kitsu outage degrades the header only, and one combined
flag would blank the whole screen for whichever is slower.

Design rules applied: the gradient samples the **wide 4.2:1 cover**, not the 3:4 poster (a poster
cropped to fill a phone shows a small central patch of colour, whereas the cover is composed for
exactly that shape); the gradient fades by 42% of the viewport so it does not tint the long unit
list; hero and unit cards use `AurisRadii.artworkLarge`/`artworkSmall`; the title is the only
place font weight is spent.

The synopsis collapses past 280 characters with a show-more toggle, because a full synopsis runs
to several paragraphs and would push the episode list — the reason most people open this screen —
entirely off-screen.

### Wiring it up

`MediaDetailViewModel` connects the three pieces:

- **Kitsu identification** fires once per entry when `kitsuId` is null. It is *triggered, not
  awaited* — the extension's title renders immediately and Kitsu's richer metadata replaces it
  on arrival. Waiting on a network call before showing a title the user just tapped feels broken.
- **Units come from the extension** via `ExtensionContentRepository`, then persist to Room. The
  stored list renders first and the fetch refreshes it, so the list appears instantly and
  survives an offline launch. A failed refresh deliberately does *not* clear the stored list.
- **Tapping a unit** navigates straight to the reader for manga (a chapter needs no resolution),
  and resolves a stream first for anime — most sources return an embed page rather than a media
  URL, and handing that to a player fails silently once playback starts. Rows are disabled while
  a resolution is in flight, so a second tap cannot orphan the first stream.

Two guards matter more than they look: `observeEntry` re-emits on every write, **including the
writes this screen causes**, so without `identificationStarted`/`unitsStarted` flags, saving a
`kitsuId` would immediately trigger a second Kitsu search, which would save nothing, and the loop
would continue.

The failure surface is kept deliberately distinct — "source not installed" (permanent) vs
"could not reach the source" (transient) vs "no episodes listed" (a real answer) — because the
user-facing message differs and collapsing them states something false.

## Logo assets

`Logos/` holds the brand sources; `app/src/main/res/drawable/` holds four generated variants.

### The filenames are inverted from their contents
Worth recording because it is genuinely confusing:

| File | Actually contains |
|---|---|
| `Auris-white.png` | **Dark ink on a light backdrop** |
| `Auris-black.png` | **Light ink on a dark backdrop** |

Both are *opaque* (logo on a solid backdrop), so neither can be composited over arbitrary
surfaces directly. `logo_mark_light`, `logo_mark_dark`, `logo_full_light` and `logo_full_dark`
are generated from them with the alpha channel synthesised from luminance, cropped to the ink's
actual bounding box, and tone-lifted.

Three things the generation had to get right, each found by rendering and looking:

- **Crop to the ink, not the canvas.** The source art has large asymmetric margins; centring on
  the canvas put the mark visibly off-centre and clipped its base.
- **Split the wordmark by finding the gap**, not by guessing a percentage of canvas height.
- **Tone-lift the light ink.** The source is a soft silver gradient on black — only 5.8% of its
  pixels cleared 50% opacity, which reads as a faint smudge at 48dp. An exponent below 1 on the
  alpha raises it to 35%. Shape and ink colour are untouched; this is a legibility fix.

### Launcher icon
Adaptive, with a flat near-black background and the **mark only** — the full lockup's wordmark is
illegible below ~96dp and renders as grey mush at 48dp. The foreground is fitted to 0.565 of the
108dp canvas rather than the theoretical 0.611, because the ribbon's flat base reaches its
bounding-box corners and fitting the diagonal overestimates the space it needs. A `monochrome`
layer is provided for themed icons.

### In-app selection
`AurisLogo` picks light-ink or dark-ink from the measured luminance of its backdrop, against the
0.5 crossover. That is also the point where either ink clears WCAG AA (4.5:1), so the choice is
the contrast requirement rather than a preference. `LocalLogoBackdrop` lets a subtree override it
for logo drawn over artwork.

## Manga reader

Vertically paged chapter images (manga reads top-to-bottom), Coil-backed, with a tap-to-reveal
progress overlay that auto-hides during playback of a *later* slice.

### Page resolution is the one inferred part
Anikku collapses manga into the anime hierarchy, so there is **no dedicated manga page API** to
call, and Anikku's extensions repo is DMCA-blocked so no real manga extension was available to
inspect. `AnimeSourceAdapter.pages()` therefore populates from the mechanism that does exist —
`getHosterList` then `getVideoList` per hoster — which is what manga sources implement, and
degrades to an empty list rather than throwing. That is the assumption to verify first against a
real manga extension.

### Pages are never cached in the model
Manga image URLs are frequently signed or session-scoped and expire within minutes. A cached URL
fails on the next open and looks like corruption, so pages are resolved per open and Coil's disk
cache handles repeat views — Coil keys on the URL, so an expired URL misses cleanly rather than
serving a stale image.

The backdrop is **fixed dark** rather than sampled from the page: adapting the chrome to page
colour would make it change on every turn, and a fixed backdrop guarantees the watermark keeps
contrast whatever the artwork is.

## Video player

`EpisodePlayer` hosts ExoPlayer with hand-built controls rather than `PlayerControlView` — the
built-in chrome is a different visual language, does not accept the app's radii or colours, and
puts a permanent scrim over the artwork the gradient rule depends on.

### One player *per episode*, which is deliberate
media3 has **no per-item HTTP header API**: `MediaItem.RequestMetadata` carries only the media
URI, a search query and extras, and `ExoPlayer` has no `setMediaSourceFactory`. Extension streams
routinely need a `Referer`, a cookie or a bearer token, so headers can only be baked into the
`HttpDataSource.Factory` at construction — meaning episode B needs a new player.

The alternative, one player with headers applied globally, would send episode A's credentials to
episode B's host. That is a correctness bug *and* a credential leak, so it was rejected. The cost
is a codec re-initialise on episode switch, which only happens on a user action. `player` is a
`StateFlow` so the UI follows whichever instance is current rather than capturing one.

### Lifecycle
Playback **pauses** in `onStop`, not `onPause` — `onPause` also fires for dialogs and the
notification shade, and pausing a video for those is wrong. Paused rather than stopped, so
returning resumes at the same position with the buffer intact.

### Format handling
`StreamTarget.mimeType` is honoured when supplied, because it distinguishes HLS from DASH from
progressive and guessing from the URL is unreliable — many sources serve HLS behind a `.php` path.
Inferred from the extension only as a fallback, which avoids a wasted request and the stall that
container sniffing causes on manifests.

## Diagnostics / log export

`Generate Logs` collects a `.txt` bundle and hands it to the share sheet via `ACTION_SEND`.

Included: device and OS build, app and database version, installed and loaded extensions with
their sources, the app's own **in-memory log buffer**, and the last 5000 logcat lines.

### Why the in-memory buffer exists
`logcat` retains only a few thousand lines system-wide. By the time a user notices something
wrong and taps the button, the interesting lines — the failed source load, the parse exception —
have usually been evicted. The buffer keeps the app's own history, bounded and evicting
oldest-first, because a logging facility that can OOM the app it is diagnosing is worse than none.

The screen shows the buffered-line count and the dropped-line count **before** exporting, so a
user can see what is about to be sent rather than discovering after the fact that the app logged
nothing.

### Redaction
`LogRedactor` is a pure, dependency-free object — the one part that must be provably correct,
since the file leaves the device through a share sheet. It masks Supabase URLs, JWTs, `Bearer`
tokens and cookies, and is tested adversarially.

The subtle bug the tests caught: each rule declares a capture group to **preserve**, and for a
bare JWT or a Supabase URL group 1 *is* the secret. Preserving it produced
`<the whole token>[redacted]` — no redaction at all, with the credential still fully present.

It deliberately does **not** redact things that merely look secret: extension package names,
class names and stack-trace file paths survive, because scrubbing them would leave most of the
file useless.

Sharing uses a `FileProvider` URI scoped to `cache/diagnostics/` only — a raw `file://` throws
`FileUriExposedException`, and exposing the whole cache would let the share sheet read extension
APKs and Coil's image cache.

## Tests

| Suite | Location | Runs on |
|---|---|---|
| `LibraryDaoTest` — identity, ordering, cascade, converters, Flow | `app/src/test/` | JVM, Robolectric |
| `ExtensionRepositoryTest` — install/uninstall/update checks | `app/src/test/` | JVM |
| `RemoteExtensionTest` — index parsing, repo-URL handling, update comparison | `app/src/test/` | JVM |
| `KitsuParsingTest` — envelope, sparse records, ids, titles, images | `app/src/test/` | JVM |
| `KitsuClientTest` — query construction, headers, errors, paging | `app/src/test/` | JVM, MockWebServer |
| `KitsuMapperTest` — attributes → `KitsuMetadata`, missing-data semantics | `app/src/test/` | JVM |
| `KitsuMetadataMatcherTest` — ranking, false positives/negatives, degenerate input | `app/src/test/` | JVM |
| `KitsuIdentifierTest` — id plumbing, caching, which fields may be written | `app/src/test/` | JVM |
| `ExtensionContentRepositoryTest` — source resolution, unit/stream fetch, persistence rules | `app/src/test/` | JVM |
| `LogRedactionTest` — credential masking, adversarially | `app/src/test/` | JVM |
| `MigrationsTest` — v2→v3 preserves rows, units, extensions, outbox | `app/src/androidTest/` | device |
| `LibrarySearchTest` — `LIKE` pattern matching | `app/src/androidTest/` | device |
| `ExtensionLoaderTest` — real APK load, id stability, parsing | `app/src/androidTest/` | device |
| `SourceAdapterTest` — adapter mapping rules | `app/src/androidTest/` | device |
| `DexFileReaderTest` — dex enumeration, corruption handling | `app/src/androidTest/` | device |

Extension tests are instrumented because **`DexClassLoader` has no JVM equivalent** — it
needs a real dalvik VM, so nothing about the loading path can be asserted under Robolectric.

Search is split out for a different reason: **Robolectric's bundled SQLite does not implement
`LIKE`/`instr` pattern matching correctly.** Verified directly — `instr('Frieren','rien')`
returns `0` under Robolectric and `3` on a device. The query itself is correct; asserting it
under Robolectric would test the harness.

Run the extension tests with `./scripts/run-extension-tests.sh` (needs a booted emulator).

`KitsuParsingTest` uses **captured real payloads**, not hand-written fixtures. A fixture
authored from the JSON:API specification would have missed every surprise in the table above —
the polymorphic `data`, the absent `links`, the `data`-less relationships, the stringified ids.

**Current: 158 JVM tests, 0 failures - 52 instrumented tests OK - conformance guard green.**
The conformance module is compiled on every build; `checkSourceApiConformance` runs it alone.

## Version pinning worth knowing

- **Kotlin 2.2.21** — forced by OkHttp 5.3.2, which Anikku extensions are compiled against and
  which ships Kotlin 2.2 metadata. An older OkHttp 5.x would avoid this but risks the same
  binary-incompatibility from the other direction.
- **Room 2.8.5** — Room 2.6.x predates Kotlin 2.2 and its KSP processor dies with
  `unexpected jvm signature V` while reading Kotlin 2.2 metadata.
- **KSP 2.2.21-2.0.5** — must match the Kotlin version exactly.
- **supabase-kt 3.0.0** — 3.8.0 ships Kotlin 2.4 metadata, newer than this toolchain.
- **androidx.browser 1.8.0** (constraint) — supabase-kt pulls it in transitively for Custom
  Tabs OAuth, and the current release demands compileSdk 36 + AGP 8.9+.
- **mockwebserver 5.3.2** — must match okhttp exactly; a mismatched version cannot parse the
  HTTP the client sends.
- **`lint { checkReleaseBuilds = false }`** — `lintVitalRelease` runs whole-project analysis as
  a release-blocking step and exhausts RAM on this machine: the build *hangs* rather than
  failing. Only the release gate is disabled; `./gradlew :app:lint` still reports.
- **kotlinx-serialization 1.9.0** — Room 2.8.x deserialises its schema bundle with a serializer
  that calls `typeParametersSerializers()`, added in serialization 1.8. Against 1.7.3 the test
  APK dies with `AbstractMethodError` the first time `MigrationTestHelper` reads a schema.

## Version control

`local.properties` holds the Supabase URL and anon key and is ignored (see `.gitignore`). Copy
`local.properties.example` and fill it in after cloning. `.toolchain/` — a local JDK, Gradle
distribution, dependency cache and AVD — is ignored too; it exists so builds are reproducible
without touching system packages.

Room's exported schemas under `app/schemas/` are deliberately **committed**:
`MigrationTestHelper` reads them from the test APK's assets, and a migration cannot be validated
without the historical schema it migrated from.

## Building on a memory-constrained machine

Tuned for 15 GB RAM with zram swap (see `gradle.properties` and `scripts/release-build.sh`).

### The failure

R8 on a release build needs several GB of heap *plus* a comparable amount of native memory that
`-Xmx` does not account for. Three things combine on a machine this size:

1. **Worker multiplication.** `org.gradle.workers.max` defaults to the core count — 16 on this
   CPU — so Gradle can run up to 16 heap-allocating tasks at once.
2. **GC choice.** G1 keeps region metadata, remembered sets and a young-generation reserve in
   *native* memory. That footprint is invisible in the heap setting and is the part that pushes
   the kernel into reclaim. SerialGC's native footprint is far smaller.
3. **The zram feedback loop**, which is the actual cause. When demand exceeds RAM the kernel
   moves pages into zram — which is itself backed by RAM. Each miss consumes RAM to compress into
   RAM, so reclaim never converges and the system oscillates. The symptom is a **stalled CPU-time
   counter**: the process is spending its time in the reclaim path, not compiling. Lowering
   `swappiness` does not fix this — the loop is self-sustaining once entered.

### The cure

Keep total demand below physical RAM so zram is never reached:

| Setting | Value | Why |
|---|---|---|
| `org.gradle.workers.max` | `1` | The single most important line. 16 workers → 1 |
| `org.gradle.parallel` | `false` | Stops the task-level fan-out compounding the above |
| `android.r8.maxWorkers` | `1` | `workers.max` bounds the executor, not R8's own pool |
| `org.gradle.jvmargs` | `-Xmx5g -XX:+UseSerialGC -XX:MaxMetaspaceSize=1g` | Small, predictable native footprint |
| `kotlin.daemon.jvmargs` | `-Xmx1500m` | The Kotlin daemon is a *separate* JVM and would otherwise be a hidden second multi-GB process |
| `-XX:+ExitOnOutOfMemoryError` | — | Fails fast rather than thrashing |
| `org.gradle.vfs.watch` | `false` | Keeps a second copy of build state resident |

`scripts/release-build.sh` adds the guarantee on top: a **`systemd-run --user --scope` with
`MemoryMax`**, so if R8 exceeds the ceiling the kernel kills the build and Gradle reports a
failure. Failing in seconds with a named error beats wedging the machine with no diagnostic. It
also reports cumulative CPU time every 45s, which is the measurement that distinguishes "slow"
from "stalled", and applies `vm.page-cluster=0` / `vm.swappiness=1` where it has the privileges.

Result on this hardware: **release build in ~4 minutes**, previously stalling indefinitely.

## Not yet implemented

Deliberately out of scope for this first slice; the seams are in place:

- **Manga page resolution is inferred, not verified.** `AnimeSourceAdapter.pages()` populates
  from `getHosterList` + `getVideoList`, which is the only path available under Anikku's
  unified hierarchy, but no real manga extension was inspectable (the repo is DMCA-blocked).
  This is the first thing to check against a live source.
- **Horizontal (RTL) manga paging** — the reader pages vertically; the common right-to-left
  horizontal variant is not implemented and should not be guessed at without a preference.
- **Reader progress persistence** — the current page is not written back to
  `library_units.last_position_ms`, so reopening a chapter restarts at page 1.
- **Fullscreen orientation** — the player has a fullscreen toggle that hides controls, but does
  not yet lock orientation or drive the system bars.
- **MediaSession service** — the player screen is complete, but nothing feeds Android Auto,
  the notification shade or lock-screen controls yet.
- **Genre browsing** — `KitsuClient.genres()` and the `*ByGenres` methods work but have no UI.
- **Kitsu metadata refresh** — `kitsuIdentifier.rankCandidates` is ready for a "this match is
  wrong, pick another" affordance that has no screen yet.
- **Extension signature verification** — the index signature and per-APK signing-key
  fingerprint are modelled but not checked. This is the main gap in the install path.
- **Repo management UI** — the URL is settable through the view model; the on-screen editor is
  not wired up yet (`onEditRepo` is a no-op).

- **InnerTube client** — YouTube Music discovery and track metadata.
- **Settings screen** — Diagnostics is currently reached from a button on the Extensions
  screen, because no Settings screen exists yet. It should move when one lands.