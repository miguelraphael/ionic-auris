Auris 0.1.6 — new anime & manga providers and source modernisation (Android 8.0 or newer).

- Integrates AllAnime built-in connector with direct adaptive HLS video streaming (.m3u8), broad sub/dub catalog, and high uptime.
- Integrates Animepahe built-in connector supporting fast airing updates, search, and lightweight encodes.
- Integrates Weeb Central manga provider with high-resolution official volume rips, chapter strips, and scanlations.
- Retires deprecated HiAnime and Animegg sources.
- Sets AllAnime as the primary default anime catalogue provider.
- Retains all security hardening, release shrinking, and non-debuggable production signing.
- Uses the same production key and `com.auris.app` package for in-place updates without clearing data.

The security, authentication, navigation, and playback changes from previous previews remain included. Older debug previews (`com.auris.app.debug`) are a separate installation; local data is not automatically transferred between packages. Legacy library items with unknown account ownership remain retained for explicit recovery; do not clear storage to troubleshoot them.

This remains a preview. Provider availability varies by network; backend authorization and broader playback/device checks remain separate from the regression checks.
