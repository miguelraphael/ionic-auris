# Account isolation and the Room v5 upgrade

The former local library and outbox were shared between accounts. The old session mirror
cannot establish who created an ownerless row: another account might already have signed in
before this upgrade. Room v5 therefore retains every ownerless row, its units, and queued work
without assigning it to an account. These records are quarantined: they are hidden from normal
library queries and cannot upload under the next account that signs in.

This preserves unsynced data on disk but does not automatically recover it into a library.
There is no recovery UI in this change. Recovery of ambiguous local-only changes requires an
explicit owner decision and a controlled database export/import; signing into an account alone
is not evidence that it owns those changes. Do not clear app storage to troubleshoot missing
legacy items before deciding whether that retained data is needed.

The migration resets the sync pull watermark so the authenticated account can reload its cloud
library. If a cloud row's ID collides with a quarantined or another account's local row, the
cloud record receives an account-specific local ID while retaining its remote ID for updates
and deletes. Repeated pulls reuse the active account's exact source identity. The retained
legacy record and queued work are not overwritten.

New local entries record their owner and use account-specific IDs. Library, units, searches,
pending counts, and sync selection are restricted to the active account. Sign-out preserves
owned local changes for the same account to resume later. Account deletion first removes the
server library, then transactionally removes that account's local rows, units, outbox, and
session mirror. It does not delete the Supabase Auth identity; that still requires a privileged
server operation. Explicit sign-out also clears the SDK's local session after best-effort
remote revocation.

Authentication and sync require the SDK's current session; a restored Room identity cannot
open the login gate or authorize a worker. Auth transitions, repository writes, and sync passes
share a mutex so a pass cannot be re-attributed during account switching. Account transitions
may wait for an in-flight sync pass to finish. Supabase RLS remains a required server-side
boundary; client isolation does not establish that deployed policies are correct.

Legacy token columns remain for schema compatibility and are scrubbed on migration/database
open. No token is copied into the Room mirror. This does not claim that the SDK's own storage
is encrypted. The library database and its identity mirror are still included in platform
backup; restoring them does not authenticate the SDK.

See [local data privacy](local-data-privacy.md) for the email/history included in platform
backup, sign-out retention, deletion limits, and local-only unit progress.

Device-local music playlists are separate from the synced Room library and retain their
existing device-local behavior. A delayed save of a brand-new public catalogue item with no
captured owner can still run under the account active when the save reaches the repository;
this is different from transferring a stored owned row or outbox operation, which is rejected.
