Auris 0.1.15 — Now Playing visual refinement, Weeb Central default, and GitHub release updater (Android 8.0 or newer).

- Now Playing screen features expanded Apple Music style album artwork proportion with a fixed 48dp dismiss chevron, bold decisive track titles, and tabular timestamp numbers.
- GPU-accelerated Canvas playback and volume sliders bypass Compose layout passes during playback ticks, with isolated recomposition scopes and removed redundant offscreen gradient allocations.
- Weeb Central is now the default manga catalog source fallback on fresh sessions, while preserving MangaDex, Asura Scans, and AllManga source switching.
- Non-intrusive GitHub release update checker added to Settings > About Auris with a manual check option, release summary snippet, and 24-hour check throttling.
- Anime and manga detail screens display English titles under the main title, with ratings and saved state feedback persisting in Library.
- Creating playlists and adding songs works with single-tap queue playback and Library grouping by media type.

The security, authentication, navigation, and playback changes from previous previews remain included. Older debug previews (`com.auris.app.debug`) are a separate installation; local data is not automatically transferred between packages.

This remains a preview. Provider availability varies by network; backend authorization and broader playback/device checks remain separate from the regression checks.
