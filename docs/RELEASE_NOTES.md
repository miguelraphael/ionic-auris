Auris 0.1.7 — anime playback fixes & direct stream resolution (Android 8.0 or newer).

- Fixes anime playback failure across AllAnime and Animepahe by resolving direct `.m3u8` adaptive HLS playlists and `.mp4` video files instead of passing raw iframe/embed HTML URLs to ExoPlayer.
- Adds multi-prefix XOR decryption (`--`, `#-`, `##`, `-#`, `#`) for AllAnime stream links and resolves `/clock.json` HLS streams with required origin/referrer headers.
- Implements JavaScript Dean Edwards unpacking for Animepahe (Kwik) and external embed hosts (StreamWish, Filemoon, Mp4Upload).
- Discards unplayable iframe pages and embed containers so only genuine playable streams reach the media player.
- Retains all security hardening, release shrinking, and non-debuggable production signing.
- Uses the same production key and `com.auris.app` package for in-place updates without clearing data.

The security, authentication, navigation, and playback changes from previous previews remain included. Older debug previews (`com.auris.app.debug`) are a separate installation; local data is not automatically transferred between packages. Legacy library items with unknown account ownership remain retained for explicit recovery; do not clear storage to troubleshoot them.

This remains a preview. Provider availability varies by network; backend authorization and broader playback/device checks remain separate from the regression checks.
