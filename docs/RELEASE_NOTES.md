Auris 0.1.10 — Asura Scans & AllManga manga sources, streamlined stream resolution (Android 8.0 or newer).

- Adds Asura Scans as a built-in manga source: browse latest titles, search the catalogue, view series details, and read chapters directly with high-performance vertical reader support.
- Adds AllManga as a built-in manga source: browse popular and trending manga, perform GraphQL-powered search, view detailed metadata, and read chapters.
- Removes obsolete Invidious dependency and instance settings; music audio streams are now resolved directly and reliably via on-device stream extraction.
- Removes Animepahe source due to persistent Cloudflare Turnstile / Managed Challenges blocking automated stream retrieval.
- Configures image loading with proper Referer and User-Agent headers for third-party manga image delivery networks.

The security, authentication, navigation, and playback changes from previous previews remain included. Older debug previews (`com.auris.app.debug`) are a separate installation; local data is not automatically transferred between packages.

This remains a preview. Provider availability varies by network; backend authorization and broader playback/device checks remain separate from the regression checks.
