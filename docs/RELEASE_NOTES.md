Auris 1.0.4 — Preview Chapter Reading & Playlist Navigation Fixes (Android 8.0 or newer).

- Seamless Manga Preview Reading: Fixed chapter tap handling in catalogue previews so unsaved manga chapters open directly in the reader without requiring the title to be saved to your Library first.
- Library Playlist Back Navigation: Tapping a playlist from the Library tab now immediately opens the playlist detail, and pressing Back returns cleanly to the Library instead of leaving the user on the Music Home screen.
- Transient Preview Safety: Unsaved manga and anime previews remain entirely fetch-only with zero database clutter or unnecessary sync queue writes.

The security, authentication, navigation, and playback changes from previous releases remain included. Older debug previews (`com.auris.app.debug`) are a separate installation; local data is not automatically transferred between packages.

This remains a preview. Provider availability varies by network; backend authorization and broader playback/device checks remain separate from the regression checks.
