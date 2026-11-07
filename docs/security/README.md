# Security deployment checks

The client cannot establish whether the deployed Supabase database enforces row-level security. Run `backend-verification.sql` in the project's SQL editor and review the complete policy set, then test with two ordinary accounts:

1. Account A creates a library row.
2. Account B attempts to select, update, and delete A's row by its known ID; every operation must return no data or fail.
3. B attempts to insert or upsert a row whose `user_id` is A; this must fail.
4. An unauthenticated client must not read either library.

Use normal account tokens for these tests, never a service-role key. Do not paste tokens or personal library data into reports. No backend policy changes have been applied by this remediation.

Extensions execute inside Auris's process with its permissions. A pinned APK certificate establishes publisher continuity, not code safety. Review publishers before installing extensions. Isolation would require a separate architecture; it is not supplied by `DexClassLoader`.

There is no independently authenticated publisher key for the default repository in this repository. Do not invent or pin a key retrieved from the same untrusted index as though it were an out-of-band trust anchor.
