# Manga reading

The Manga tab uses a built-in MangaDex client. No extension repository, API key, or MangaDex
login is required for this source. It uses the public API directly from the device.

## User flow

1. Open Manga. Popular and Latest show titles with English chapters; search accepts a title.
2. Tap a title to save it in the unified library and open its details and chapter list.
3. Select a chapter. Swipe vertically between pages; tap to show Back and chapter controls.
4. Open the title again from Home or Library to select a chapter and resume its saved page.

The catalogue supports Load more. This initial source includes safe and suggestive catalogue
ratings and English chapters. Empty chapters and links to external publisher readers are
excluded. Availability depends on what MangaDex currently provides; not every title has readable
English chapters. This does not provide general Mihon extension compatibility or arbitrary URL imports.

## Implementation

- `MangaDexSource`: catalogue/search, cover metadata, paginated chapter feeds, fresh image-server
  resolution on chapter open. It implements the existing `MediaSource` and `MangaReaderSource`
  contracts, without changing the anime extension ABI or music playback.
- `ExtensionContentRepository`: resolves built-in sources as well as extensions. MangaDex chapter
  UUIDs are saved as `sourceUnitId`. Existing indices, read markers, bookmarks and page positions
  survive refresh; new chapter UUIDs receive unused indices. Newly discovered earlier chapters
  consequently appear after previously saved chapters in the current ordinal-based library schema.
- `MangaReaderViewModel`: metadata collection and page loading run independently. A missing chapter
  shows an error instead of silently opening a different chapter. Retry resolves images again.
- For manga only, `library_units.last_position_ms` stores the zero-based page index. The legacy
  column name is retained to avoid a database migration. Audio/video continue to use milliseconds.
- Manga library cards open details so chapter selection is always reachable. The current chapter
  is highlighted there. The reader provides Previous chapter and Next chapter controls.

The reader displays loading and failure states per image. Chapter lists and reading positions
are stored locally. Offline image downloads, zoom, alternate reading directions and additional
source/language selection are separate follow-up work.

## Verification

Run deterministic source and reader regression tests with:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*MangaDexSourceTest' --tests '*MangaReaderViewModelTest'
```

The source tests use MockWebServer and cover query encoding, pagination, fractional/repeated
chapter numbers, external/empty chapters, image ordering, fresh server addresses, rate limits,
and refresh preserving reading progress. Reader tests cover initial loading despite an ongoing
metadata stream, saved-page restoration, retry, page changes and missing chapters.

For a separate, read-only check against the live public service:

```powershell
python scripts/manga-source-smoke.py
```

That check requests Popular, Search, Latest, a chapter feed, an image-server response and actual
image bytes. It saves JSON responses under ignored `.toolchain/manga-smoke/`. Live checks depend
on network/service availability and do not substitute for testing the Android screens on a device.
