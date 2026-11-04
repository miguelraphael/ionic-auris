package com.auris.app.data.repository

import com.auris.app.data.local.SyncDao
import com.auris.app.data.local.entity.SyncSessionEntity
import com.auris.app.data.remote.SupabaseLibraryDataSource
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val remote: SupabaseLibraryDataSource,
    appScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Null when signed out — this is what gates the app. */
    val session: Flow<SyncSessionEntity?> = syncDao.observeSession()

    private val _sessionExpired = MutableStateFlow(false)

    /**
     * True when the stored session ended without the user asking.
     *
     * supabase-kt clears its own token pair when a refresh is rejected (revoked or expired
     * refresh token), emitting [SessionStatus.NotAuthenticated]. The Room mirror is deliberately
     * left alone on that path, so a non-null mirror at that moment is what distinguishes an
     * involuntary end from an explicit sign-out.
     *
     * Not a sign-out: the local library stays readable and editable offline, and a user may
     * choose to finish what they are doing before re-authenticating. The shell turns this into
     * a dismissible dialog.
     */
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    init {
        // Token refreshes land here: supabase-kt emits a new Authenticated
        // status after each rotation, and the mirror follows so the stored
        // pair never goes stale. A RefreshFailure is transient by definition
        // (`NetworkError` or `InternalServerError`) and is left to the offline
        // and error states; signing out stays explicit, and local work must
        // survive.
        appScope.launch {
            auth.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        _sessionExpired.value = false
                        runCatching { persist(status.session) }
                    }

                    is SessionStatus.NotAuthenticated -> {
                        // Sign-out clears the Room mirror too, so a row that outlives
                        // NotAuthenticated means the session ended underneath the user.
                        _sessionExpired.value = syncDao.currentSession() != null
                    }

                    // Initializing and RefreshFailure do not change the verdict: neither says
                    // anything certain about the stored session.
                    else -> Unit
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
        // Explicit sign-out is not an expiry. Reset after the clear so a collector that ran
        // between the auth status change and the Room delete cannot leave the flag set.
        _sessionExpired.value = false
    }

    /**
     * Deletes the account's server library, signs out, and clears the session.
     *
     * ### Why no `auth.admin.deleteUser`
     * That call needs the service_role key, which must never ship in the app —
     * and GoTrue offers no self-delete for regular users. So deletion here
     * means: every row owned by this user is removed (RLS owner policy), then
     * the session is revoked and dropped, which routes back to the login gate
     * through the session flow. The `auth.users` identity itself can only be
     * removed server-side (dashboard or a privileged function).
     *
     * ### All-or-nothing ordering
     * The remote wipe runs first because it needs the session: once signed
     * out, RLS denies everything. If the wipe throws (offline, rejected), the
     * session is kept and the error surfaces — signing out anyway would orphan
     * server data under a live account with no way back to it.
     */
    suspend fun deleteAccount(): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val userId = auth.currentSessionOrNull()?.user?.id
                ?: syncDao.currentSession()?.userId
                ?: error("Not signed in")
            remote.deleteAllEntries(userId)
            runCatching { auth.signOut(SignOutScope.GLOBAL) }
            syncDao.clearSession()
            _sessionExpired.value = false
        }
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
