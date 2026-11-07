package com.auris.app.data.remote

import com.auris.app.data.sync.PermanentSyncException
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Real PostgREST requests prove page filters and tie-break cursors, without a live backend. */
class SupabaseLibraryDataSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var client: SupabaseClient
    private lateinit var remote: SupabaseLibraryDataSourceImpl

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        client = createSupabaseClient(server.url("/").toString().removePrefix("http://").trimEnd('/'), "test-anon") {
            useHTTPS = false
            install(Postgrest)
        }
        remote = SupabaseLibraryDataSourceImpl(client.postgrest)
    }

    @After fun close() {
        runBlocking { client.close() }
        server.shutdown()
    }

    private fun row(id: Int, at: String = "2026-11-07T00:00:00.123456Z") = LibraryEntryRow(
        id = id.toString().padStart(5, '0'), userId = "account-a", mediaType = "MUSIC",
        sourceId = "innertube", sourceKey = id.toString(), title = "Track $id", updatedAt = at,
    )

    private fun page(rows: List<LibraryEntryRow>) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody(Json.encodeToString(ListSerializer(LibraryEntryRow.serializer()), rows)))
    }

    @Test fun pullsMoreThan500RowsIncludingTimestampTiesUsingExactKeysetCursor() = runTest {
        val first = (1..500).map { row(it) }
        page(first)
        page(listOf(row(501)))
        page(emptyList())

        val all = remote.fetchEntries("account-a", 1794009600123L)

        assertEquals(501, all.size)
        assertEquals(501, all.map { it.remoteId }.toSet().size)
        val initial = server.takeRequest(2, TimeUnit.SECONDS)!!.requestUrl!!
        assertEquals("eq.account-a", initial.queryParameter("user_id"))
        assertEquals("gte.2026-11-07T00:00:00.123Z", initial.queryParameter("updated_at"))
        assertEquals("updated_at.asc.nullslast,id.asc.nullslast", initial.queryParameter("order"))
        assertEquals("500", initial.queryParameter("limit"))
        val second = server.takeRequest(2, TimeUnit.SECONDS)!!.requestUrl!!
        val cursor = second.queryParameter("or")!!
        assertTrue(cursor.contains("updated_at.gt.2026-11-07T00:00:00.123456Z"))
        assertTrue(cursor.contains("updated_at.eq.2026-11-07T00:00:00.123456Z"))
        assertTrue(cursor.contains("id.gt.00500"))
        assertFalse(second.queryParameterNames.contains("offset"))
        val third = server.takeRequest(2, TimeUnit.SECONDS)!!.requestUrl!!
        assertTrue(third.queryParameter("or")!!.contains("id.gt.00501"))
    }

    @Test fun shortServerPagesDoNotTruncateThePull() = runTest {
        page(listOf(row(1), row(2)))
        page(listOf(row(3)))
        page(emptyList())

        assertEquals(listOf("00001", "00002", "00003"),
            remote.fetchEntries("account-a", null).map { it.remoteId })
        assertEquals(3, server.requestCount)
    }

    @Test fun malformedTimestampFailsInsteadOfSubstitutingWallClockWatermark() = runTest {
        page(listOf(row(1, "not-a-time")))
        try {
            remote.fetchEntries("account-a", null)
            fail("Invalid timestamp must not be imported with a fabricated watermark")
        } catch (_: PermanentSyncException) { }
    }
}
