Auris 0.1.3 — release startup hotfix (Android 8.0 or newer).

- Fixes an immediate startup crash in the signed/minified 0.1.2 release: Kotlin reflection could not resolve Ktor's `createClientPlugin` after R8 changed its API descriptor names.
- Preserves the reflected Ktor plugin API and Kotlin function-interface names. Release minification and non-debuggable production signing remain enabled.
- Uses the same production key and `com.auris.app` package, allowing an in-place update from 0.1.2 without clearing data.

The security and playback changes from 0.1.2 remain included. Older debug previews (`com.auris.app.debug`) are a separate installation; local data is not automatically transferred between packages. Legacy library items with unknown account ownership remain retained for explicit recovery; do not clear storage to troubleshoot them.

This remains a preview. Provider availability varies by network; backend authorization and broader playback/device checks remain separate from the startup regression check.
