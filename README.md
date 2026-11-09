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

Auris is built from scratch in Kotlin and Jetpack Compose. It bridges music streaming, anime playback, and manga reading into a single cohesive experience with zero telemetry, local-first SQLite persistence, and artwork-led dynamic palettes.

## Pillars

### Music
- **Fullscreen Now Playing**: Proportional artwork presentation, 48dp dismiss chevron, tabular timestamp counters, and dynamic gradients extracted from album covers.
- **GPU-Accelerated Controls**: Canvas playback and volume sliders render directly on the draw phase, bypassing Compose layout passes during audio ticks.
- **Browse & Queueing**: Search YouTube Music catalogues, manage play queues, create playlists, and organize library collections.

### Anime
- **Stream Discovery**: Stream episodes directly through integrated extensions, with clean stream resolution and adaptive format support.
- **Metadata Enrichment**: Kitsu integration identifies community ratings, synopses, and English/original titles alongside seasonal listings.

### Manga
- **Ephemeral Preview Reader**: Read chapters directly from catalogue browsing without cluttering your library.
- **Reading Controls**: Top-to-bottom long-strip paging, automatic system-bar hiding for full-screen immersion, and instant chapter synchronization when saved.
- **Multi-Source Support**: Weeb Central, MangaDex, Asura Scans, and AllManga integrations.

## Architectural Principles

- **Local-First (Room)**: Room is the single source of truth. UI state observes reactive `Flow` streams from SQLite; network calls populate the database rather than passing raw payloads to the screen.
- **Background Outbox Sync**: Mutations write immediately to the local outbox and sync to Supabase in the background with automatic retry and conflict quarantine.
- **Artwork-Led Palette**: Screen backdrops sample dynamic color roles extracted from active album and media covers, preserving contrast across pure dark surfaces.
- **Native Android Expectations**: 48dp minimum touch targets, predictive system Back support, edge-to-edge window insets, and no ported Cupertino controls.

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
