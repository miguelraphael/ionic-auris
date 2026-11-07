# Anime and manga sources

Use **Change source** at the top of the Anime or Manga tab. The app remembers each
choice on this device. An existing search is repeated on the selected provider, so a
source outage does not force you to retype the title.

| Type | Provider | Supported content |
| --- | --- | --- |
| Anime | HiAnime | Existing sub/dub catalog; ZokoAnime servers |
| Anime | Animegg | Japanese anime (sub/dub); high definition direct MP4 streams |
| Manga | MangaDex | Existing English-chapter catalog |
| Manga | MangaPill | Trending titles, latest chapter updates, search, and chapter images |

These are built-in connectors, not new extension compatibility. Providers have different
catalogs: Animegg provides an alternative catalog of Japanese anime alongside HiAnime. Episodes
without a playable stream target report an error rather than pretending another embed
is playable. Source availability can differ by network and region.

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
