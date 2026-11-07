Auris 0.1.4 — authentication and keyboard fixes (Android 8.0 or newer).

- Fixes release authentication failing before its first HTTP request because R8 removed generic types required by Ktor reflection.
- Keeps sign-in and registration forms scrollable above the keyboard.
- Replaces internal exception text with readable authentication errors and keeps diagnostic logging free of request contents.
- Retains the startup fix from 0.1.3, release shrinking, and non-debuggable production signing.
- Uses the same production key and `com.auris.app` package for in-place updates without clearing data.

The security and playback changes from 0.1.2 remain included. Older debug previews (`com.auris.app.debug`) are a separate installation; local data is not automatically transferred between packages. Legacy library items with unknown account ownership remain retained for explicit recovery; do not clear storage to troubleshoot them.

This remains a preview. The signed release was tested on an API 34 emulator: keyboard-visible form scrolling and an invalid login reaching Supabase passed. Successful real-account sign-in/registration, backend authorization, and broader playback/device checks remain unverified. Provider availability varies by network.
