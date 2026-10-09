# `:token-mint` — module blueprint

Design only. Nothing here is built, and the module is blocked on the YouTube ToS decision
recorded in `ARCHITECTURE.md` §4.

---

## 1. What it exists to do

Obtain a **PO token** for one video id, on demand, and hand it to the app. A PO token is what
YouTube's `/player` and Google Video Server endpoints require before they will serve media.

Two constraints this must not violate:

- **No GPL-3.0 code.** Nothing enters the dependency graph that would relicense the app.
- **No WebView inside `MediaSessionService`.** The service must never host a browser.

---

## 2. Module boundary

```
:token-mint                     :app
┌──────────────────────┐        ┌───────────────────────────┐
│ MintActivity         │        │ MediaSessionService       │
│  (transparent,       │  IPC   │  consumes cached tokens   │
│   noHistory)         │◄──────►│  knows nothing about Web  │
│                      │        │                           │
│ MintWebViewClient    │        │ InnerTubeClient           │
│  loads YouTube's own │        │  attaches token to request│
│  JS and returns the  │        └───────────────────────────┘
│  token               │
│                      │
│ TokenCache           │
└──────────────────────┘
```

**`MintActivity` is `Theme.Translucent.NoTitleBar` + `noHistory="true"` + `excludeFromRecents`.**
It exists only to own a WebView long enough for one mint, then finishes. The user should see, at
most, a brief "preparing playback…" state — never a visible browser.

**Why an Activity and not a Service or a `WebView` in the app process:** a `WebView` requires a
`Context` with a window, and only an Activity provides one that is cheap to create and destroy.
Hosting it in a separate *process* (`android:process=":mint"`) means a WebView crash or an OOM in
the renderer cannot take the playback service down.

---

## 3. The IPC boundary

**ContentProvider, not AIDL.** AIDL would give a typed interface, but the entire contract is three
operations and a token blob; `ContentProvider` gets those with no `.aidl` file, no
`Stub`/`asInterface` boilerplate, and thread-safe calls for free.

```kotlin
// :token-mint, exported only to :app, permission-guarded
class TokenProvider : ContentProvider() {

    /** Returns the cached token for [videoId], or null. */
    override fun query(uri, projection, selection, args, sort): Cursor?

    /** Starts a mint for [videoId]. Returns immediately. */
    override fun insert(uri, value: ContentValues): Uri?

    /** Registers an observer; notified when a token lands. */
    override fun registerContentObserver(observer: ContentObserver)
}
```

The **service-side contract** is deliberately minimal. A consumer needs exactly:

```
tokenFor(videoId: String): String?      // cached, or null
mint(videoId: String): Unit             // fire and forget
observe(videoId: String): Flow<String>  // resolved later
```

The service calls `tokenFor`, gets `null`, calls `mint`, and observes. It never learns that a
WebView was involved.

**Security:** the provider is `exported="true"` but guarded by a signature-level permission
declared in `:app`, so only this app can call it. A token is short-lived and bound to one video
id, so leaking one has a small blast radius — but it should still not be readable by other apps.

---

## 4. Option 3 — mint strictly on demand, one token per track

PO tokens are **bound to the video id** and live for hours, so there is no "mint once, reuse"
option. The cost is inherent: one mint per track the user actually plays.

```
user taps a track
      │
      ▼
┌─────────────────────────────────────────┐
│ InnerTubeClient.resolveAudioStream()    │  existing, unchanged
└─────────────────────────────────────────┘
      │ 200 UNPLAYABLE, no formats
      ▼
┌─────────────────────────────────────────┐
│ TokenProvider.tokenFor(videoId)         │
└─────────────────────────────────────────┘
      │ null
      ▼
┌─────────────────────────────────────────┐
│ TokenProvider.mint(videoId)             │  starts MintActivity in :mint process
└─────────────────────────────────────────┘
      │
      ▼  (WebView loads YouTube's own JS)
┌─────────────────────────────────────────┐
│ MintWebViewClient.evaluateJavascript()  │
└─────────────────────────────────────────┘
      │ token
      ▼
┌─────────────────────────────────────────┐
│ TokenCache.put(videoId, token, expiry)  │
│ notify observers                        │
└─────────────────────────────────────────┘
      │
      ▼
┌─────────────────────────────────────────┐
│ retry resolveAudioStream(token)         │
└─────────────────────────────────────────┘
      │ stream
      ▼
   play
```

### Rules that make it correct

1. **The service is never blocked.** `mint` returns immediately; the retry happens on a callback,
   so the notification and transport controls stay live while a mint is in flight.
2. **One mint in flight at a time.** A queue pops tracks as they are played, not upfront. If a mint
   fails, the UI says so — the service does not retry silently.
3. **Cache with expiry, keyed by video id.** A token is only useful for the video it was minted
   for, so a naive "cache the last token" is wrong. Store `videoId → (token, expiresAt)`.
4. **Nothing is minted speculatively.** No warming the next track during playback — that is the
   only way to avoid paying a mint for a track the user never hears.

### The honest cost

First play of any track pays one mint. That is a visible latency spike the user will feel, and it
is not architecturally removable — only hideable, by warming during playback, which trades latency
for cost. Option 3 chooses cost over latency.

---

## 5. What this does not solve

**The ToS question.** This design makes WebView-based minting technically and legally separable
from the rest of the app, which is the right structure. It does not make using YouTube this way
permitted. `ARCHITECTURE.md` §4 records that as open, and it should be settled before this module
is written.

**Stability.** The token flow depends on YouTube's internal JS and its BotGuard, both of which
change without notice. Every release of `:token-mint` should be treated as potentially broken until
proven live — the same discipline the extension ABI work required.
