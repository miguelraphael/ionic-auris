Auris 1.0.3 — Read without saving (Android 8.0 or newer).

- Manga chapters and anime episodes now open directly from the catalogue preview without being added to your Library — the chapter/episode is read and watched with no library row created until you explicitly save.
- Progress writes for unsaved previews are no-ops, so browsing never pollutes the Library or sync outbox.

The security, authentication, navigation, and playback changes from previous releases remain included. Older debug previews (`com.auris.app.debug`) are a separate installation; local data is not automatically transferred between packages.

This remains a preview. Provider availability varies by network; backend authorization and broader playback/device checks remain separate from the regression checks.
