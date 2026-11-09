# Auris

<p align="center">
  <strong>Native Android media client unifying music, anime, and manga in a dark-only, Apple Music-inspired aesthetic.</strong>
</p>

<p align="center">
  <a href="https://github.com/miguelraphael/ionic-auris/actions/workflows/android.yml"><img src="https://github.com/miguelraphael/ionic-auris/actions/workflows/android.yml/badge.svg?branch=main" alt="Android CI" /></a>
  <a href="https://github.com/miguelraphael/ionic-auris/releases"><img src="https://img.shields.io/github/v/release/miguelraphael/ionic-auris?color=10B981&label=Release" alt="Latest Release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-blue.svg" alt="GPL-3.0 License" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-green.svg" alt="Android 8.0+" />
</p>

---

Auris is built from scratch in Kotlin and Jetpack Compose. It bridges music streaming, anime playback, and manga reading into a single cohesive experience with zero telemetry, local-first SQLite persistence, and Supabase-housed sync.

Current release: **1.0.2** (versionCode 19). See [Releases](https://github.com/miguelraphael/ionic-auris/releases) and `docs/RELEASE_NOTES.md`.

## Pillars

### Music
- **Catalogue and on-device audio**: YouTube Music's InnerTube (`WEB_REMIX`) catalog, with audio streams extracted on-device via NewPipe. No login required to browse.
- **Playback**: Media3 media-session service, background queue with rapid start, queue advance on track end, and automatic Recently Played recording for every played track.
- **Fullscreen Now Playing**: Proportional artwork presentation, 48dp dismiss chevron, tabular timestamps, and artwork-led dynamic tinting.
- **Local playlists (Room)**: Full CRUD — create, list, rename, delete, add/remove tracks — persisted in SQLite with v5→v6 migration and SharedPreferences import. Entries are stored by session-independent video id (never stream URLs).

### Anime
- **Stream discovery**: Integrated extensions plus built-in AllAnime (HLS `.m3u8` playback) and other anime sources. Animepahe was removed after persistent Cloudflare challenges blocked stream retrieval; the stream extractor handles token forms and domain resilience.
- **Explicit Library save**: browsing a catalogue title opens a preview; a title is only added to the library when you tap **Save to Library** — no silent "visit = add".
- **Metadata enrichment**: Kitsu community ratings, synopses, and English/original title lines on detail screens (ratings are repaired and persisted when a saved row lacks one).

### Manga
- **Read without saving**: chapters open directly from catalogue preview; the chapter is persisted only when you explicitly save.
- **Long-strip reader**: continuous vertical chapters with bounded decoding, automatic system-bar hiding, and instant chapter synchronization when saved.
- **Multi-source support**: Weeb Central (default), MangaDex, MangaPill, Asura Scans, and AllManga. Image delivery uses Referer/User-Agent headers per CDN.

## Architectural Principles

- **Local-First (Room)**: Room is the single source of truth. UI state observes reactive `Flow` streams from SQLite; network calls populate the database rather than passing raw payloads to the screen. Local playlists use their own tables with a `playlist_tracks` junction and cascade deletes.
- **Background Outbox Sync**: Mutations write immediately to the local outbox and sync to Supabase in the background with automatic retry and conflict quarantine.
- **Auth gate**: The library syncs per user — there is no anonymous mode, and sign-out stops music playback (and removes the notification) on the device.
- **Artwork-Led Palette**: Screen backdrops sample dynamic color roles extracted from active album and media covers, preserving contrast across pure dark surfaces.
- **Native Android Expectations**: 48dp minimum touch targets, predictive system Back support (album/artist context survives round-trips through Now Playing), edge-to-edge window insets, and no ported Cupertino controls.

## Installation

Download the latest signed APK from **[GitHub Releases](https://github.com/miguelraphael/ionic-auris/releases/latest)**.

- **Minimum Version**: Android 8.0 (API 26)
- **Target Version**: Android 15 (API 35)
- **Supported Architectures**: `arm64-v8a`, `armeabi-v7a`, `x86_64`

## Building from Source

### Prerequisites
- JDK 17
- Android SDK 35 (Platform 35, Build-Tools 35.0.0)

### Quick Start
```bash
# Clone the repository
git clone https://github.com/miguelraphael/ionic-auris.git
cd ionic-auris

# Configure credentials
cp local.properties.example local.properties
# Edit local.properties with your SUPABASE_URL and SUPABASE_ANON_KEY

# Build debug APK and run tests
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

### Verification status
- **Unit tests**: 541 JVM tests pass (7 skipped, 0 failures) — targeted suites for playlists, detail preview, Kitsu, and music/player all green.
- **Build/lint**: `assembleDebug` and `lintDebug` pass with 0 errors (pre-existing warnings only).
- **Signed release**: built by CI for `v*` tags; `docs/RELEASE_NOTES.md` is the release-notes source.
- **Device**: RMX3636 (Android 15) physical checks pass for launch, migration, and playback-stable playback.

## Module Structure

| Module | Role |
|---|---|
| `:app` | Primary application (Compose UI, ViewModels, Room DAOs, Media3 playback service) |
| `:source-api` | Vendored compatibility layer for extension integration |
| `:source-api-conformance` | Compile-time validation asserting compatibility contracts |
| `:mock-extension` | Test APK fixture verifying dynamic DexClassLoader isolation |

Detailed wire protocol notes, schema migrations, and sync worker specifications are documented in [`ARCHITECTURE.md`](ARCHITECTURE.md). Historical debug logs and technical journal notes are archived in [`docs/HISTORICAL_NOTES.md`](docs/HISTORICAL_NOTES.md).

## License

Auris is licensed under the [GNU General Public License v3.0 or later](LICENSE). Audio extraction uses [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPL-3.0-or-later).
