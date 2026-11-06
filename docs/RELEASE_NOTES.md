Auris brings music, anime, and manga into one Android app (Android 8.0 or newer).

### Highlights in v0.1.1 (UI & Media Experience Enhancements):

- **Detail Hero Backdrop**: Automatic fallback to blurred poster banner (`20.dp` blur with darkened scrim) when cover art is unavailable for manga and anime titles, paired with sharp foreground poster cards.
- **Redesigned Continue Shelf**: Restored full 2:3 vertical poster artwork on resume cards, accompanied by title, media badge, progress percentage indicator, and attached click-to-resume touch target.
- **Unified Catalogue Browsing**: Eliminated harsh scroll clipping under the search bar on MangaDex and Anime catalogues by hosting header, mode tabs, and search as spanned grid items.
- **Interactive Manga Reader Controls**: Added interactive page scrubber slider with real-time page indicators (`Page X of Total`), percentage indicator, and previous/next chapter actions.
- **Now Playing Artist Navigation**: Tapping the artist name on the Now Playing screen navigates directly to the dedicated artist profile, biography, top tracks, and release collections.
- **Fluid Motion Transitions**: Added buttery-smooth parallax horizontal slide transitions and tab crossfades across the entire app.

This is a **debug-signed preview APK** (`com.auris.app.debug`), not a production-signed release.
CI requires the Supabase client configuration and passes unit tests before publishing.
Debug signing keys may differ between build environments, which can prevent installing over an existing copy. Preserve local data before any uninstall.

Verification limits: recent mini-player layout changes and live album/artist playback still need on-device verification. Source availability depends on third-party services. The attached test phone supports 60 Hz; higher-refresh-rate coverage is unverified.
