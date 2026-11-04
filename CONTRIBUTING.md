# Contributing to Auris

Auris is an Android client for anime, manga and music. It is Kotlin + Jetpack Compose,
local-first, and licensed GPL-3.0-or-later.

## Where the code lives

- **Canonical repository:** <https://git.private.coffee/anju/ionic-auris> (Forgejo, private)
- **Contribution mirror:** <https://github.com/miguelraphael/ionic-auris> (GitHub, public)

**You do not need a `git.private.coffee` account.** Work on GitHub:

1. Fork the GitHub repository.
2. Branch from `main`.
3. Open a pull request against `main`.

`main` on GitHub is a **force-updated mirror** of the Forgejo repository. That has three
consequences:

- Never push to `main` on the mirror; the next sync overwrites it.
- The maintainer reviews the PR, merges it into Forgejo, and pushes the mirror. A merged
  PR may close automatically; otherwise it is closed after the sync.
- Your fork and your PR branch are unaffected by the force push. Rebase on `main` if the
  mirror has moved; do not merge `main` into your branch.

Issues are welcome on GitHub. Forgejo-only discussions are not visible to contributors
without an account, so use GitHub for anything you want a response to.

## Before you build

- **JDK 17** (the project targets Java 17)
- **Android SDK 35** and **build-tools 35.0.0**
- Use the Gradle **wrapper** (`./gradlew`); do not install a separate Gradle

```bash
cp local.properties.example local.properties
# set sdk.dir to your Android SDK, and optionally SUPABASE_URL / SUPABASE_ANON_KEY
```

`local.properties` is git-ignored. Supabase values are optional for building and testing:
without them the app compiles and the tests run, but authentication and sync will fail at
runtime. Only the **anon** key belongs in a client. Never commit a service-role key, a
keystore, or any other credential.

The maintainer's `.toolchain/` directory (local JDK, Gradle cache, AVD) is also ignored.
Do not add it to the repository or rely on it in scripts.

## Build and test

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:testDebugUnitTest      # JVM tests — the per-PR gate
./gradlew :app:connectedDebugAndroidTest   # needs a device or emulator
```

On a low-memory machine, add:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest \
  --no-daemon --no-configuration-cache \
  -Dorg.gradle.jvmargs="-Xmx3g"
```

CI (`.github/workflows/android.yml`) runs `:app:assembleDebug` and
`:app:testDebugUnitTest` on every push to `main` and every pull request, and uploads the
APK and test report. The workflow does not run on Forgejo, so the GitHub check is a
pre-review signal; the maintainer runs the same tests before merging.

## Making a change

- **One logical change per PR.** Keep unrelated reformatting out of the diff.
- **Branch names:** `fix/…`, `feat/…`, `docs/…`, `refactor/…`.
- **Commit subject:** imperative, at most 72 characters, no trailing period.
- **Commit body:** explain *why* the change is needed, what you considered, and any
  trade-off. The history is read as documentation; a body that only restates the diff is
  not useful.
- **UI changes:** attach before/after screenshots or a short recording.
- **Tests:** add or update tests for behaviour changes. Do not delete or weaken a test to
  make CI pass; explain in the PR if a test is genuinely obsolete.

## Code rules

These are not preferences; the architecture depends on them.

- **Room is the only source the UI reads.** Network results go through a repository into
  Room, and the UI observes a `Flow`. Never hand a network response to a composable.
- **Writes are write-through + outbox.** A repository writes the row and its
  `sync_outbox` entry in one transaction; sync happens in the background.
- **No `NavController` in ViewModels.** Navigation is a UI concern; ViewModels expose
  state and events.
- **Manual dependency injection.** The graph is small and singleton-only; do not introduce
  Hilt or another DI framework.
- **Non-destructive Room migrations.** Add a migration and the exported schema under
  `app/schemas/`; never use `fallbackToDestructiveMigration` to make a change compile.
- **Client-side only.** There is no self-hosted server in this project.
- **Compose Material 3.** Match the existing theme (`ui/theme/`) and the shared shapes and
  colours instead of introducing one-off values.
- **Comments explain why**, not what. The code already says what it does.
- **Dependencies:** justify a new one in the PR, check that its licence is compatible with
  GPL-3.0-or-later, and prefer an existing dependency over a new one.

## Testing notes

- The JVM suite covers repositories, sync, parsing, redaction and cover lookup without a
  device.
- `connectedDebugAndroidTest` is slower and needs an emulator; run it for changes that
  touch Room queries or Android framework behaviour.
- Migration tests read the committed schemas; if you change an entity, commit the new
  schema JSON as well.

## Licensing

Auris is **GPL-3.0-or-later** because it links NewPipeExtractor. By submitting a
contribution you agree that it may be distributed under that licence. Only submit code
you have the right to license; say so in the PR if a change is based on another project.

## Security

Do not open a public issue for a vulnerability. Contact the maintainer privately first so
a fix can be prepared before disclosure.
