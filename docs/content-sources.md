# Anime and manga sources

Use **Change source** at the top of the Anime or Manga tab. The app remembers each
choice on this device. An existing search is repeated on the selected provider, so a
source outage does not force you to retype the title.

| Type | Provider | Supported content |
| --- | --- | --- |
| Anime | AllAnime | Comprehensive sub/dub anime library; direct HLS (.m3u8) playback streams |
| Anime | Animepahe | Fast airing releases and lightweight encodes via Pahe & Kwik streams |
| Manga | MangaDex | Existing English-chapter catalog |
| Manga | Weeb Central | Official digital releases, volume rips, scanlations, and full chapter image strips |
| Manga | MangaPill | Trending titles, latest chapter updates, search, and chapter images |

These are built-in connectors, not new extension compatibility. Providers have different
catalogs: AllAnime provides a massive library with direct adaptive HLS video streaming,
while Animepahe provides fast airing updates and efficient encodes. Episodes without a
playable stream target report an error rather than pretending another embed is playable.
Source availability can differ by network and region.

Changing the tab's provider does not rewrite saved titles. Library entries and progress
remain tied to their original source. To use another provider for a saved title, search
there and explicitly select the matching title; it is stored separately. The app does
not guess episode/chapter equivalence across providers.

MangaPill's image requests need its public Referer header. Chapter page requests carry
per-page headers, and the app's Coil image loader applies the required Referer only to
MangaPill's known image CDN so saved posters also load.

## Verification

Deterministic tests cover source markup, pagination, episode/chapter ordering, image
headers, embed resolution, unsupported hosts, search preservation, and cancellation of
old source requests. Separate live tests exercise the production connectors:

```bash
AURIS_LIVE_SOURCES=1 ./gradlew :app:testDebugUnitTest --tests '*AlternativeSourcesLiveTest'
```

Live tests fetch small image/video samples. Success on a build machine does not prove
Android decoding or availability on another network; those require device checks.
