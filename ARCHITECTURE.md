# Auris — Architecture Handover

Everything here reflects verified behaviour at commit `3e97555`. Where something is **not**
working, it says so plainly rather than describing intent.

---

## 1. Room database schema

Single source of truth. The UI observes Flows; WorkManager pushes to Supabase when online. Current
version **4**. Exported schemas live in `app/schemas/` and are committed deliberately —
`MigrationTestHelper` reads them from androidTest assets.

### `library_entries` — one row per title

Unifies anime, manga and music so progress looks identical across pillars.

| Column | Type | Notes |
| --- | --- | --- |
| `id` | TEXT PK | Local identity, not the remote's |
| `remote_id` | TEXT | The source's own id, scoped by `source_id` |
| `source_id` / `source_key` | TEXT | Extension package / provider key |
| `media_type` | TEXT | `ANIME` / `MANGA` / `MUSIC` |
| `kitsu_id`, `kitsu_slug` | TEXT? | Set by `KitsuIdentifier`; present since v1 |
| `kitsu_cover_url` | TEXT? | Wide 4.2:1 art, distinct from the 3:4 `artwork_url` |
| `kitsu_original_title` | TEXT? | Source-language title |
| `kitsu_rating` | REAL? | 0.0–100.0 |
| `artwork_url` / `local_artwork_path` | TEXT? | Remote poster vs cached file |
| `genres`, `categories` | TEXT | Comma-joined, `NOT NULL` |
| `progress`, `score` | REAL, INTEGER | |
| `total_units`, `current_unit_index` | INTEGER | **1-based** — see below |
| `status`, `sync_state` | TEXT | Enum, stored by name |
| `is_deleted`, `is_favorite` | INTEGER | Boolean-as-int |
| `last_position_ms` | INTEGER | Resume position |
| timestamps | INTEGER | `created_at`, `updated_at`, `last_activity_at`, `remote_updated_at` |

**`current_unit_index` is 1-based.** An album at track 2 of 5 therefore shows 40%, exactly as an
anime at episode 2 of 5 does. That is the whole reason the music pillar can share this table.

### `library_units` — episodes / chapters / tracks

Composite key `(entry_id, unit_index)`. Carries `source_unit_id`, `title`, `thumbnail_url`,
`duration_ms`, `is_watched`, `last_position_ms`, `bookmark`.

> **`stream_url` is never persisted for music.** InnerTube URLs carry `expiresInSeconds` of roughly
> six hours, so storing one guarantees a 403 on the next play. The unit keeps only the video id and
> the player re-resolves per playback.

### `sync_outbox`, `sync_sessions`

`sync_outbox` is pending work with `dedupe_key` — losing it means a change silently never reaches
Supabase, so migration tests assert rows survive. `sync_sessions` holds the Supabase token pair.

### `installed_extensions`

`signature_hash` (SHA-256 of the **APK file**) and `signing_cert_fingerprint` (SHA-256 of the
**signing certificate**) are deliberately **separate columns**. The file hash proves the bytes did
not change; it is computed by whoever supplies the file, so it cannot prove who signed them.
Overwriting one with the other would destroy a check while appearing to add a security property.

### Migrations

`MIGRATION_1_2`, `MIGRATION_2_3`, `MIGRATION_3_4`, all in `Migrations.ALL`, none destructive.
`MigrationsTest` deletes the database file in `@Before` so the suite is order-independent — another
suite opening the app's real database otherwise breaks whichever test runs next.

> **Type converters must never throw.** All three enums fall back to the first constant on an
> unknown value. `valueOf` throwing while Room is *reading* propagates out of the observing
> coroutine and kills the process: an enum rename made the app unlaunchable until app data was
> cleared. An enum name in SQLite is an external contract that outlives the code that wrote it.

---

## 2. ExoPlayer lifecycle

### Episode player — rebuilt **per episode**

`EpisodePlayer` constructs a fresh `ExoPlayer` per episode rather than reusing one. media3 has no
per-item HTTP header API and `ExoPlayer` has no `setMediaSourceFactory`. A single player carrying
global headers would send episode A's credentials to episode B's host. This is a deliberate
security decision, not an oversight.

Released when the route is popped. `setWakeMode(C.WAKE_MODE_NETWORK)` keeps the CPU awake across a
track without preventing sleep between tracks.

### Music player — `MusicPlaybackService`

A media3 `MediaSessionService`, so playback survives the Activity and reaches the notification
shade, lock screen and system media controls.

## 3. MediaSessionService constraints

- **Audio focus is mandatory.** `AudioAttributes` with `USAGE_MEDIA` / `CONTENT_TYPE_MUSIC` and
  `setAudioFocus(true)`. Without it ExoPlayer does not request focus and a phone call talks over
  the music instead of ducking it.
- **`setHandleAudioBecomingNoisy(true)`** — pauses on headphone unplug. The single most reported
  music-app bug.
- **Release in `onDestroy`, never `onUnbind`.** Unbind happens on every rotation.
- **`onTaskRemoved` does not stop playing music.** Swiping the app away should not kill playback;
  it stops only when idle.
- **`foregroundServiceType="mediaPlayback"`** plus the matching permission, required from Android 14.
- **The notification comes from `DefaultMediaNotificationProvider`.** Hand-rolling transport
  pending-intents means targeting `SessionCommands` constants that **moved between media3
  releases** — the first attempt failed to compile against 1.5.1 exactly that way, and the failure
  mode of getting it subtly wrong is a notification whose buttons silently do nothing. Only the
  channel id is app-owned, and it is stable so upgrades keep user preferences.

---

## 4. BotGuard and the `/player` wall

**Music playback does not work and cannot until stream resolution is solved.**

`/player` returns `UNPLAYABLE` with an empty `adaptiveFormats` for every unauthenticated client
tested live: `WEB_REMIX` and `ANDROID_MUSIC` (200 + `UNPLAYABLE`/`LOGIN_REQUIRED`), and
`FAILED_PRECONDITION` for `WEB`, `MWEB`, `IOS`, `TVHTML5_SIMPLY_EMBEDDED_PLAYER`,
`WEB_EMBEDDED_PLAYER`, `ANDROID_VR`. Passing a genuine `visitorData` did not help, so this is not a
missing header.

Per the yt-dlp PO Token Guide (July 2026), PO tokens are now **bound to the video id** — one per
track — and serving audio requires BotGuard attestation. Running BotGuard means defeating an access
control, so it is **not attempted here**, and it would compound an unresolved YouTube ToS question.

The app reports this honestly: `YouTube declined to serve audio for "…" (UNPLAYABLE)`, not
"Nothing playable", which would point the user at a track that is fine. `StreamRefusal` separates a
per-track condition (`private`, `removed`, region-blocked) from a request-level refusal, because
only the first is fixed by choosing a different song.

Browse, search, metadata and library all work. Only audio does not.

### Decision: YouTube audio extraction is DEFERRED

**Status: not implemented in this release, deliberately.** Two independent blockers, either of
which alone is sufficient:

**1. Licensing.** Every viable route is GPL-3.0. Auris has **no `LICENSE` file** — all rights
reserved by default — so linking one would force the entire app to GPL and open-source. That is the
project owner's call, not an engineering one, and it has not been made.

**2. Technical.** PO tokens are now **bound to the video id**, so serving audio requires BotGuard
attestation per track. BotGuard is an anti-automation control; defeating it is circumventing an
access control. It is also incompatible with the architecture: minting needs a WebView, and a
WebView cannot run inside a `MediaSessionService` — which is precisely what background audio
requires. **The fix would break the feature it was meant to restore.**

### What was evaluated

| Option | Outcome |
| --- | --- |
| NewPipeExtractor | **Rejected.** GPL-3.0. No YouTube Music support, so it discards the album/track model and the `totalUnits` progress mapping. WebView PO-token flow cannot run in a `MediaSessionService`. |
| ArchiveTune (6.9k stars) | **Rejected.** Verified from its own `libs.versions.toml`: it depends on `com.github.TeamNewPipe:NewPipeExtractor`. So it inherits the same GPL-3.0 and the same WebView constraint — plus it is an *app*, not a library, so nothing is importable. |
| Obsidian-Music | **Irrelevant.** Apache-2.0 and Media3-based, but the repository contains **no source** — only `README.md`, `LICENSE`, `update.json` and screenshots. It is also a *local-file* player with no streaming at all. |
| BotGuard attestation | **Not attempted.** Circumventing an access control. |

ArchiveTune is the most useful data point in that table, and not for the reason it might appear: its
own legal disclaimer states it **"does not bypass YouTube's technical protections"** — it
*authenticates*. So a 6.9k-star client reaching the same wall and solving it with an account is
strong evidence that **authentication is the only route**, and that there is no bypass to be found.
That makes this a product decision about accounts, not an outstanding engineering task.

### If this is revisited

The only unblocked path is user-supplied authenticated cookies (their own account). That is a ToS
call for the project owner, and the UI work is: a sign-in screen, cookie persistence in the existing
`sync_sessions`-style secure storage, and expiry handling in `StreamRefusal`. `InnerTubeClient` is
already built so this is a config change — but **it should be an explicit decision, never inherited
from a GPL app.**

---

## 5. ⚠️ ABI drift — read this before changing `:source-api`

> **Thirteen ABI breaks have now been found, and a fully green build passed through every one of
> them.** The cause was structural, not bad luck: `mock-extension` is written against our own
> interfaces, so it matches by construction. **Every "real APK" claim in this project was
> self-referential until real extensions were loaded.**

| # | Break | Why nothing caught it |
| --- | --- | --- |
| 1 | `AnimeFilterList` was a `typealias` | Emits **no JVM class**. Declared, compiled, unusable |
| 2 | `AnimeFilter.state` typed `Boolean`/`Int` | Compiles to `()Z`/`()I`; extensions call `getState()()Ljava/lang/Object;` |
| 3 | `NetworkHelper` was an `interface` | Exists, but extensions `invokevirtual` it |
| 4 | `ParsedAnimeHttpSource.network` absent | No declaration to notice |
| 5 | `ConfigurableAnimeSource` absent | Same |
| 6 | Aniyomi 16+ has **no factory at all** | References `AnimeSourceFactory` **zero** times |
| 7 | `network` on the wrong class | Belongs on `AnimeHttpSource`, read off the base from a constructor |
| 8 | `AnimeFilter$Header`/`Separator` absent | Fails `getFilterList()` wholesale |
| 9 | `AnimeFilter` subtypes were `final` | Kotlin default; extensions subclass them |
| 10 | `RequestsKt.GET/POST` lacked `CacheControl?` | Compiles fine, fails at first request |
| 11 | `OkHttpExtensionsKt`, `Track`, `asJsoup(charset)` | Each one member at a time |
| 12 | `Video` was an `interface` | Interface, no constructor: `InstantiationError`. Found by the fixture that finally worked. |
| 13 | v16 `Video` 16-param constructor absent | `NoSuchMethodError`. **Fixed** — see below |

**Every one presented identically to a user: the extension loads and registers zero sources.**

### Rules

1. **A `typealias` is not a class.** Anything an extension can reference must be a real class.
2. **A declaration check is not an ABI check.** Kotlin signatures can stay valid while the emitted
   descriptor changes. Assert descriptors.
3. **File facades are ABI.** Extensions link against `RequestsKt`, `OkHttpExtensionsKt`. Moving a
   function between files breaks linking with a `NoClassDefFoundError` that reads like a missing
   class.
4. **Fingerprint normalisation is load-bearing.** Repos publish `AB:CD:EF:…`; `ApkSigner` emits
   bare lower-case hex. Comparing raw strings would reject every real extension — and the failure is
   indistinguishable from a forgery.
5. **Read shapes from the dex, never from memory.** Every one of the eleven was found by inspecting
   a real APK. All of my initial guesses were wrong in ways only the binary settled.

### The guard, and its known hole

`SourceApiAbiTest` asserts descriptors. `SourceApiAbiGuardTest` exists because **a guard that cannot
fail is not a guard** — writing it found that `Class.getDeclaredMethod` matches on name and
parameters only and **never compares the return type**, so the guard would have passed on exactly
the drift it exists to catch.

`DexAbiReader` — which would derive expectations from a real dex automatically — is committed
**incomplete and `@Ignore`d**. It parses string and type tables but not `proto_ids` parameters.

> **Do not make it green until it actually parses.** A guard reporting "no gaps" because it parsed
> nothing is worse than no guard: it is indistinguishable from a perfect API, which is how eleven
> gaps survived a green build.

Two notes for anyone finishing it: `proto_id_item` is **8 bytes** (ushort, ushort, uint), not 12 —
at a 12-byte stride the decoded values are header offsets like `20716` (`class_defs_off`). And
`RandomAccessFile.read()` **throws** at EOF rather than returning −1.

---

## 6. Current verification state

| Suite | Result |
| --- | --- |
| JVM | 278 tests, 4 skipped, **0 failures** |
| Device (instrumentation) | **67 pass** |
| `RealVideoResolutionTest` | **3 of 3 pass** — video resolution proven |
| `RealCatalogExtractionTest` | **6 of 7 pass** — sole failure is animetake, on the Cloudflare block |
| Conformance (`:source-api-conformance`) | green — but see §5: it cannot see incompleteness |

### What works, proven against real extensions

Loads and registers · popular · latest · search · details · mapping through `AnimeSourceAdapter`
into our own model · unknown-entry resilience · **the full video-resolution chain** — catalogue →
details → episode → `getHosterList` → `getVideoList` → absolute URL.

Two extension generations are covered: **v14** (animekhor, the fixture that proved video
resolution) and **v16** (animetake, which exercises the 14-parameter `Video` constructor). Neither
of the two West Coast fixtures can load — that is the Mihon ecosystem, a different API generation.

### ✅ What works — video resolution is PROVEN

**`AnimeSourceAdapter.pages()` is now observed against a live source.** The catalogue →
details → episode → `getHosterList` → `getVideoList` → absolute-URL chain runs end to end, and the
result additionally survives mapping through the adapter into our own model, so a pass cannot come
from the extension alone. **3 of 3 `RealVideoResolutionTest` tests pass.**

The fixture that made it work:

| Fixture | Extension | Why it worked |
| --- | --- | --- |
| `real-flat-episode-extension.apk` | `eu.kanade.tachiyomi.animeextension.en.animekhor` v14.29 | Flat episodes (`episodeListParse`, **no** `seasonListParse`), no credentials, and a live site serving real episode links. It goes through Dailymotion, so the dex references Dailymotion's GraphQL hoster API — which is why it contains `password` and `oauth/token` strings. Those are the **hoster's** concerns, not an extension login. |

The two that failed, kept as the record of what was ruled out:

| Fixture | Extension | Shape | Outcome |
| --- | --- | --- | --- |
| `real-anime-catalog-extension.apk` | `…en.animetake` v16.9 | **Season-based.** Declares `seasonListParse`, overrides no `getEpisodeList`. | `getSeasonList` returns empty for every real title tried, so episodes — which hang off a season — are never reached. Also needs the 16-parameter `Video` constructor below, so it cannot serve the chain even if seasons did. |
| *(superseded)* | `…en.kimoitv` v14.1 | Flat, no login. | Loaded and registered, but its site returned no entries for popular, latest or search. Dead endpoint, superseded in the same fixture slot. |

### Gap #12, found by the working fixture

`Video` was an **interface**, so it could not be instantiated. Extensions construct videos
directly — `Video(url, quality, videoUrl, headers, subtitleTracks, audioTracks)` — and the call
site died with `InstantiationError: …model.Video`. Now an `open class` with that exact
constructor plus the three members the dex reads back (`ffmpegStreamArgs`, `mpvArgs`,
`videoTitle`), verified descriptor-for-descriptor with `javap`.

Two details that only the running test settled, both of which a from-memory guess would have got
wrong:

- **`videoUrl` must be computed, not stored.** Captured from the constructor argument, it goes
  stale the moment the host assigns `url` afterwards — which is how the mock extension and every
  `Video()`-then-assign caller work, so the streams all resolved to the empty string.
- **`headers` is `okhttp3.Headers`, not `Map<String, String>`.** The host converts at
  `toStreamTarget()`, since the ABI type is the one that appears in the descriptor.

### What is still unverified

**Manga page extraction.** `AnimeSourceAdapter.pages()` is now proven for **anime**. The manga
branch is the same method but was not exercised by this run, and the manga-side extraction path
(`getHosterList` for a manga source) remains unverified against a live source. The Keiyoushi
fixture is a different API generation and cannot load — see §5.

### Gap #13 — the v16 `Video` constructor — CLOSED via a secondary constructor and synthetic bridge

v16 extensions call a 14-parameter constructor, with the `int` mask and `DefaultConstructorMarker`
that Kotlin appends for defaulted parameters making 16 arguments in the dex call:

```
Video(String, String, Integer, Integer, Headers, boolean, List×6, String, boolean, int, DefaultConstructorMarker)
```

Our `Video` had only the 6-parameter form, so every v16 source died with `NoSuchMethodError`. It is
now a secondary constructor with those 14 parameters and matching defaults, which makes Kotlin emit
the synthetic bridge itself rather than hand-writing one. **Both constructors now coexist**, verified
descriptor-for-descriptor with `javap`: v14 sources (animekhor) still use the 6-argument primary,
v16 sources use this one.

The parameter *names* are provisional — only the shapes are known, from the descriptor and the
constants at the call site — and the code says so, because the names are not what linking checks
and a confident-but-wrong name would be misleading.

### Why animetake fails: a Cloudflare JS challenge, not our code

Previously listed as "unknown whether the season layer has a parse gap or the site lists none". It
is the **site**, established without guessing:

- `seasonListParse` descriptors match ours **exactly** — `(Lokhttp3/Response;)Ljava/util/List;` — so
  the extension does override it and it does get called.
- `https://animetake.tv/anime/one-piece` returns **HTTP 200, 969 KB**, titled `One Piece -
  AnimeTake` — so it is the real page and the request works.
- That response contains **zero** occurrences of `season`/`staffel`/`saison` and **zero**
  `/episode/` links, alongside Cloudflare markers and a `challenge` reference.

So the page is a client-rendered / bot-filtered shell: a plain HTTP client receives markup with no
episode data in it. There is nothing for `seasonListParse` to find. **This is a site-side
constraint, not an ABI gap and not a parsing bug** — the same class of problem as Kitsu's
DMCA'd repo rather than anything in `:source-api`.

### Environment note

The Android SDK lives at `/home/ana/android-sdk`, **not** `/tmp` — `/tmp` is a tmpfs and wiped it
twice. Build via `scripts/release-build.sh`; R8 is memory-constrained and needs the serialised
settings in `gradle.properties`.
