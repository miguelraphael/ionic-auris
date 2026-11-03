# Anime streaming

The Anime tab now browses a built-in connector for `hianime.at`. It supports Popular, Latest,
search and pagination. Selecting a title saves it to the unified library and opens its episode
list. Selecting an episode opens the internal player.

## Supported playback

- The connector currently understands the **ZokoAnime** embed format observed on this source.
  Other listed hosts are not presented as playable without a working resolver.
- Sub and Dub streams are available through the source button where the episode provides them.
- Subtitle tracks supplied by the server are attached to Media3. The CC button toggles them.
- HLS and DASH playback modules are included. HLS chooses among the quality variants actually
  present in its playlist; it cannot create a higher resolution than the source supplies.
- The player loads the saved episode's `sourceUnitId`, rather than an empty or reconstructed link.
  Resolution occurs once at player entry, and Retry resolves fresh links again.
- Reading/watching progress is saved locally. Anime positions use milliseconds and enqueue
  progress updates with media type ANIME. Reopening a saved episode resumes its stored position.

The source is a website connector, not a promise that every anime or episode is available.
Domain, embed format, network restrictions and server availability can change. This is not
general support for arbitrary pasted links or every third-party extension.

## Startup and server performance

Removing duplicate episode resolution avoids a second pair of source requests before playback.
Playback starts with a 1-second buffer and can accumulate up to 50 seconds to absorb interruptions.
The source is contacted directly from the device; Auris does not run a proxy server.

The live check measured real video bytes and playlist response times. Those measurements describe
one machine and request, not sustained throughput or a universal fastest-server ranking. Playlist
requests can still take several seconds. No untested "fast server" URL is hardcoded.

## Verification

Deterministic tests use MockWebServer for catalogue parsing, query encoding, fractional episode
numbers, duplicate episode markup, the observed embed payload, subtitles, headers, unsupported
hosts, and invalid episode identities.

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

The opt-in Kotlin live test runs the production connector through catalogue, search, episodes,
stream resolution, HLS manifests, a small MPEG-TS sample, and English WebVTT subtitles:

```powershell
$env:AURIS_LIVE_ANIME = '1'
.\gradlew.bat :app:testDebugUnitTest --tests '*HiAnimeLiveTest'
```

A separate reproducible Python check is available:

```powershell
python scripts/anime-source-smoke.py
```

Both network checks sample at most 64 KiB of a video segment. JSON/HTML and timings from the Python
check are stored under ignored `.toolchain/anime-smoke/`; transient streaming URLs are not stored
in the library or committed as fixtures.

The opt-in device test `LiveAnimePlaybackTest` additionally checks whether Media3 renders a real
video frame to an Android surface. Run only that class with instrumentation argument
`aurisLiveAnime=true` on a device/emulator. It is skipped in ordinary instrumentation runs.

Verified for this change: the full unit suite passed, the opt-in Kotlin live test passed, both
debug APKs built, and the device test passed on the project-local Android emulator. The decoder
reported a first frame at 1056 × 800 for the tested episode. This verifies native playback of
that stream, rather than claiming that every catalogue entry or server has been tested.
