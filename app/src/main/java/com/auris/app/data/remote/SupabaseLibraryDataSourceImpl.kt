package com.auris.app.data.remote

import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.data.sync.PermanentSyncException
import com.auris.app.data.sync.TransientSyncException
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant

/**
 * PostgREST implementation of [SupabaseLibraryDataSource].
 *
 * ### API shape (supabase-kt 3.0.0)
 * `select` / `upsert` / `delete` are `suspend` and return a `PostgrestResult` directly —
 * there is no `execute()`. Row filters are not on the request builder itself; they go
 * through the nested `filter { }` DSL, which is where `eq` / `gt` live.
 *
 * ### Error policy
 * 3.x throws on non-2xx rather than returning a failure value, so this class owns the
 * transient-vs-permanent split that the sync worker keys off:
 *  - [HttpRequestException] / [HttpRequestTimeoutException] / [IOException] /
 *    5xx / 408 / 429 -> [TransientSyncException] (WorkManager retries).
 *  - any other 4xx -> [PermanentSyncException]: the row is quarantined rather than retried,
 *    because an RLS rejection or schema mismatch will not resolve itself.
 */
class SupabaseLibraryDataSourceImpl(
    private val postgrest: Postgrest,
) : SupabaseLibraryDataSource {

    override suspend fun upsertEntry(userId: String, entry: LibraryEntryEntity) {
        withContext(Dispatchers.IO) {
            val row = entry.toRow(userId, System.currentTimeMillis())
            call("upsert ${entry.id}") {
                postgrest.from(TABLE).upsert(row)
            }
        }
    }

    override suspend fun deleteEntry(userId: String, entryId: String) {
        withContext(Dispatchers.IO) {
            call("delete $entryId") {
                postgrest.from(TABLE).delete {
                    filter {
                        eq("id", entryId)
                        eq("user_id", userId)
                    }
                }
            }
        }
    }

    override suspend fun deleteAllEntries(userId: String) {
        withContext(Dispatchers.IO) {
            call("delete all for $userId") {
                postgrest.from(TABLE).delete {
                    filter {
                        eq("user_id", userId)
                    }
                }
            }
        }
    }

    override suspend fun fetchEntries(userId: String, since: Long?): List<LibraryEntryEntity> {
        return withContext(Dispatchers.IO) {
            val rows: List<LibraryEntryRow> = call("pull") {
                postgrest.from(TABLE).select {
                    filter {
                        eq("user_id", userId)
                        if (since != null) {
                            // High-water mark: only rows changed since the last
                            // successful pull, so restarts don't re-download everything.
                            gt("updated_at", iso8601(since))
                        }
                    }
                    order("updated_at", order = Order.DESCENDING)
                    limit(PULL_PAGE)
                }.decodeList()
            }
            // A row this client can't decode (written by a newer app version) is skipped
            // rather than failing the whole pull.
            rows.mapNotNull { row -> runCatching { row.toEntity() }.getOrNull() }
        }
    }

    private companion object {
        const val TABLE = "library_entries"
        const val PULL_PAGE = 500L

        fun iso8601(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

        suspend fun <T> call(operation: String, block: suspend () -> T): T = try {
            block()
        } catch (e: HttpRequestException) {
            throw TransientSyncException("$operation network failure: ${e.message}", e)
        } catch (e: IOException) {
            throw TransientSyncException("$operation network failure: ${e.message}", e)
        } catch (e: RestException) {
            val status = e.statusCode
            if (status >= 500 || status == 408 || status == 429) {
                throw TransientSyncException("$operation failed (HTTP $status): ${e.message}", e)
            }
            throw PermanentSyncException("$operation rejected (HTTP $status): ${e.message}", e)
        }
    }
}