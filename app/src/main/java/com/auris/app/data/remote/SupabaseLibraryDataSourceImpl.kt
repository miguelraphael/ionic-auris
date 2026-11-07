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
            val entries = mutableListOf<LibraryEntryEntity>()
            var cursor: LibraryEntryRow? = null
            while (true) {
                val after = cursor
                val rows: List<LibraryEntryRow> = call("pull") {
                    postgrest.from(TABLE).select {
                        filter {
                            eq("user_id", userId)
                            // Millisecond watermarks are inclusive so sub-millisecond rows
                            // and ties at the previous boundary are not lost.
                            if (since != null) gte("updated_at", iso8601(since))
                            if (after != null) {
                                or {
                                    gt("updated_at", after.updatedAt!!)
                                    and {
                                        eq("updated_at", after.updatedAt!!)
                                        gt("id", after.id)
                                    }
                                }
                            }
                        }
                        order("updated_at", order = Order.ASCENDING)
                        order("id", order = Order.ASCENDING)
                        limit(PULL_PAGE)
                    }.decodeList()
                }
                if (rows.isEmpty()) break
                // Never substitute wall-clock now for an invalid server timestamp: that
                // would advance the watermark past rows this device has not imported.
                for (row in rows) {
                    val at = runCatching { Instant.parse(row.updatedAt).toEpochMilli() }
                        .getOrElse { throw PermanentSyncException("Invalid remote updated_at", it) }
                    entries += row.toEntity().copy(updatedAt = at, remoteUpdatedAt = at)
                }
                val last = rows.last()
                check(after == null || last.updatedAt != after.updatedAt || last.id != after.id) {
                    "Remote pull cursor did not advance"
                }
                cursor = last
                // Even a short page may be a server-configured limit below PULL_PAGE.
                // Continue until an empty page rather than silently truncating the pull.
            }
            entries
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
