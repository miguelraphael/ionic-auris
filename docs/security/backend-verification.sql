-- Read-only deployment check. Run in the project's Supabase SQL editor.
-- Client filters are not an authorization boundary. Review every policy below:
-- SELECT/DELETE USING and INSERT/UPDATE WITH CHECK must enforce auth.uid() = user_id.
-- A permissive policy can OR with other permissive policies and defeat isolation.
SELECT schemaname, tablename, policyname, permissive, roles, cmd, qual, with_check
FROM pg_policies
WHERE schemaname = 'public' AND tablename = 'library_entries';

SELECT n.nspname AS schema_name, c.relname AS table_name,
       c.relrowsecurity AS rls_enabled, c.relforcerowsecurity AS force_rls
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relname = 'library_entries';

-- Check grants too; anonymous users should have no access to account libraries.
SELECT grantee, privilege_type
FROM information_schema.role_table_grants
WHERE table_schema = 'public' AND table_name = 'library_entries'
  AND grantee IN ('anon', 'authenticated');
