package com.auris.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auris.app.TestApplication
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.data.local.entity.SyncSessionEntity
import com.auris.app.data.remote.SupabaseLibraryDataSource
import com.auris.app.data.repository.AuthRepository
import com.auris.app.data.sync.SyncScheduler
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.status.SessionStatus
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = TestApplication::class)
class AuthSessionGateTest {
    @Test
    fun restoredRoomIdentityCannotOpenGateOrAuthorizeSyncWithoutSdkSession() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AurisDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            db.syncDao().saveSession(SyncSessionEntity("restored-user", email = "private@example.test"))
            val status = MutableStateFlow<SessionStatus>(SessionStatus.NotAuthenticated(false))
            val sdk = Proxy.newProxyInstance(Auth::class.java.classLoader, arrayOf(Auth::class.java)) { _, method, _ ->
                when (method.name) {
                    "getSessionStatus" -> status
                    "currentSessionOrNull" -> null
                    else -> error("Unexpected SDK call: ${method.name}")
                }
            } as Auth
            val remote = object : SupabaseLibraryDataSource {
                override suspend fun upsertEntry(userId: String, entry: LibraryEntryEntity) = error("No network expected")
                override suspend fun deleteEntry(userId: String, entryId: String) = error("No network expected")
                override suspend fun deleteAllEntries(userId: String) = error("No network expected")
                override suspend fun fetchEntries(userId: String, since: Long?): List<LibraryEntryEntity> = error("No network expected")
            }
            val repository = AuthRepository(sdk, db.syncDao(), SyncScheduler(context), remote,
                backgroundScope, ioDispatcher = Dispatchers.Unconfined)

            assertNull(repository.session.first())
            assertNull(repository.currentAuthenticatedSession())
            assertNull(db.syncDao().currentSession())
        } finally {
            db.close()
        }
    }
}
