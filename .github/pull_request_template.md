## What changed

<!-- One or two sentences. Link the issue if there is one. -->

## Why

<!-- The problem this solves. If it fixes a bug, what was the cause? -->

## How it was tested

- [ ] `./gradlew :app:assembleDebug`
- [ ] `./gradlew :app:testDebugUnitTest`
- [ ] Exercised on a device or emulator (describe what you did)

<!-- Connected tests, if relevant: ./gradlew :app:connectedDebugAndroidTest -->

## Screenshots / recordings

<!-- Required for UI changes: before and after. Delete this section otherwise. -->

## Checklist

- [ ] One logical change; no unrelated reformatting
- [ ] New behaviour has tests, and no existing test was weakened or deleted
- [ ] No secrets, keystores, `local.properties`, or service-role keys
- [ ] If a Room entity changed: a migration was added and the schema JSON committed under `app/schemas/`
- [ ] I read `CONTRIBUTING.md`

## Notes for the maintainer

<!--
GitHub is a mirror: do not merge this PR here. The maintainer merges it into
Forgejo and the mirror updates. If the mirror moved, rebase your own branch and
force-push only that branch, never `main`.
-->
