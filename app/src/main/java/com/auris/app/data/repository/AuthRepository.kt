package com.auris.app.data.repository

import com.auris.app.data.local.SyncDao
import com.auris.app.data.local.entity.SyncSessionEntity
import com.auris.app.data.sync.SyncScheduler
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Email/password authentication against Supabase Auth, mirrored into Room.
 *
 * ### Two sources of truth, deliberately
 * supabase-kt persists its own token pair and refreshes it (`Auth` owns
 * refresh, so no caller here ever handles a token). `sync_sessions` mirrors
 * the *identity* — user id plus the current tokens — so the sync worker can
 * gate on "signed in" with a Room read and address rows to the right owner
 * without touching the network stack. The mirror is one-way: auth state
 * flows from supabase-kt into Room, never back.
 *
 * ### Why sign-out always clears locally
 * A sign-out that needs the network strands the user signed in on airplane
 * mode. The server revocation is best-effort; the local row is always
 * dropped, which is what actually gates the app.
 */
class AuthRepository(
    private val auth: Auth,
    private val syncDao: SyncDao,
    private val syncScheduler: SyncScheduler,
    appScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Null when signed out — this is what gates the app. */
    val session: Flow<SyncSessionEntity?> = syncDao.observeSession()

    init {
        // Token refreshes land here: supabase-kt emits a new Authenticated
        // status after each rotation, and the mirror follows so the stored
        // pair never goes stale. Never clears: transient states
        // (Initializing, RefreshFailure) must not sign the user out —
        // sign-out is explicit only.
        appScope.launch {
            auth.sessionStatus.collect { status ->
                if (status is SessionStatus.Authenticated) {
                    runCatching { persist(status.session) }
                }
            }
        }
    }

    /** Registers and signs in, unless the project requires email confirmation. */
    suspend fun signUp(email: String, password: String): Result<SignUpOutcome> =
        withContext(ioDispatcher) {
            runCatching {
                auth.signUpWith(Email) {
                    this.email = email.trim()
                    this.password = password
                }
                // Confirmation-required projects return no session here: the
                // account exists but the user must confirm, then sign in.
                if (auth.currentSessionOrNull() != null) {
                    persistCurrent()
                    SignUpOutcome.SignedIn
                } else {
                    SignUpOutcome.ConfirmEmailRequired
                }
            }
        }

    suspend fun signIn(email: String, password: String): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                auth.signInWith(Email) {
                    this.email = email.trim()
                    this.password = password
                }
                persistCurrent()
            }
        }

    suspend fun signOut() {
        runCatching { auth.signOut(SignOutScope.GLOBAL) }
        withContext(ioDispatcher) { syncDao.clearSession() }
    }

    private suspend fun persistCurrent() {
        val session = auth.currentSessionOrNull() ?: return
        persist(session)
        // Wake the worker: a fresh sign-in should pull the server library
        // now, not on the next periodic pass.
        runCatching { syncScheduler.requestSync() }
    }

    private suspend fun persist(session: UserSession) {
        val user = session.user ?: return
        syncDao.saveSession(
            SyncSessionEntity(
                userId = user.id,
                email = user.email,
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }
}

/** Whether a registration completed with a session or needs confirmation. */
enum class SignUpOutcome {
    SignedIn,
    ConfirmEmailRequired,
}
