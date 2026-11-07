Auris 0.1.5 — navigation refinements, album exploration, and sync hardening (Android 8.0 or newer).

- Removes redundant back buttons on top-level Anime and Manga catalogue tabs.
- Redesigns the catalogue "Change source" action into an integrated glass chip with an in-app bottom sheet selector.
- Replaces the media detail season dropdown with a modal bottom sheet to prevent upward menu clipping over artwork.
- Expands album search results from YouTube Music and enables direct navigation to album details and track listings on tap.
- Resolves missing artist attribution for tracks played from albums.
- Hardens sync operations with atomic cleanups and confirmed tombstone purges while retaining local modifications during progress uploads.
- Enforces package path and code verification for extension installations.
- Retains all security hardening, release shrinking, and non-debuggable production signing.
- Uses the same production key and `com.auris.app` package for in-place updates without clearing data.

The security, authentication, and playback changes from previous previews remain included. Older debug previews (`com.auris.app.debug`) are a separate installation; local data is not automatically transferred between packages. Legacy library items with unknown account ownership remain retained for explicit recovery; do not clear storage to troubleshoot them.

This remains a preview. Provider availability varies by network; backend authorization and broader playback/device checks remain separate from the regression checks.
