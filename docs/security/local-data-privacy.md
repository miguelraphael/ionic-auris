# Local data, sign-out and platform backup

Auris keeps `auris.db` in its private app storage. It contains account identifiers and the
session mirror's email address, library titles and metadata, artwork URLs, playback/reading
history and progress, and queued uploads. It is not an encrypted database. The Android app
sandbox and device security protect access; an owner-scoped query is not disk encryption.

## Backup and restore

The database is intentionally eligible for Android cloud backup and device transfer to retain
unsynced local changes and quarantined legacy data. Whether cloud backup runs depends on the
device's backup settings and provider. A backup or transferred copy includes the email address,
history and pending uploads above. Anyone who can access that backup or the restored app's
private storage may obtain them. Use a trusted device/backup account, and disable app backup
through the device's backup controls if this retention is unwanted.

Android backup XML selects files, not SQLite tables or columns: excluding `sync_sessions` or
just its email field would require a separate database or a custom backup implementation.
The current rules keep the existing recovery behavior rather than discard the whole library
to exclude that one table. Supabase SDK token storage is not included by these database-only
rules; current access/refresh tokens are never copied into the Room session mirror, and legacy
token columns are scrubbed on database open. A restored identity row does not authenticate the
user: the login gate still requires the SDK session. See [account migration](account-migration.md).

## Sign-out and deletion

Sign-out clears the SDK session and Room session mirror, but deliberately retains account-owned
library/history and pending work so the same user can resume local changes later. Those rows
are hidden from other signed-in accounts. Ownerless legacy data remains quarantined until an
explicit recovery decision. Sign-out is not a local-data wipe; on a shared or resold device,
remove the app's storage only after preserving any unsynced or legacy data that is still needed.

Account deletion first removes the account's server library, then transactionally removes its
local entries, units, and queued work. Unit deletion is explicit and does not depend solely on
SQLite foreign-key enforcement. Orphaned queued payloads are removed only when their recorded
owner matches the deleted account; malformed/ownerless legacy payloads are retained because
their ownership cannot safely be inferred. Other accounts' data is preserved. This operation
does not remove the Supabase Auth identity or erase older platform backups; those require
the relevant server/backup controls. See the account-migration document for the existing
account-deletion contract.

## Unit progress

Episode/chapter/track unit rows, including individual watched ticks, are local-only. The server
contract syncs entry-level progress, current unit index and resume position; it has no units
table. Unit rows can move with a device backup, but do not independently sync across devices.
