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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Email/password authentication against Supabase Auth, mirrored into Room.
 *
 * ### SDK session is authoritative
 * supabase-kt persists its own token pair and refreshes it (`Auth` owns
 * refresh, so no caller here ever handles a token). `sync_sessions` mirrors
 * the *identity* — user id and email — so the sync worker can
 * gate on "signed in" with a Room read and address rows to the right owner
 * without touching the network stack. Tokens are managed exclusively by
 * supabase-kt's SDK-managed storage and are never mirrored into Room or backup archives.
 * The mirror is one-way: auth state flows from supabase-kt into Room, never back.
 *
 * ### Why sign-out always clears locally
 * A sign-out that needs the network strands the user signed in on airplane
 * mode. The server revocation is best-effort; the local row is always
 * dropped and the authenticated session flow is cleared.
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
    private val authenticatedSession = MutableStateFlow<SyncSessionEntity?>(null)
    private val initialized = CompletableDeferred<Unit>()
    val session: Flow<SyncSessionEntity?> = flow {
        initialized.await()
        emitAll(authenticatedSession)
    }

    /** A restored Room mirror alone never authorizes network sync. */
    fun currentAuthenticatedSession(): SyncSessionEntity? = authenticatedSession.value?.takeIf {
        auth.currentSessionOrNull()?.user?.id == it.userId
    }

    private val _sessionExpired = MutableStateFlow(false)

    /** True when SDK authentication expires; the gate closes while owned local data is retained. */
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    init {
        // Token refreshes land here: supabase-kt emits a new Authenticated
        // status after each rotation; only its identity is mirrored into Room. A RefreshFailure is transient by definition
        // (`NetworkError` or `InternalServerError`) and is left to the offline
        // and error states; signing out stays explicit, and local work must
        // survive.
        appScope.launch {
            auth.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        _sessionExpired.value = false
                        AccountAccess.mutex.withLock { persist(status.session) }
                        initialized.complete(Unit)
                    }

                    is SessionStatus.NotAuthenticated -> {
                        // Sign-out clears the Room mirror too, so a row that outlives
                        // NotAuthenticated means the session ended underneath the user.
                        AccountAccess.mutex.withLock {
                            if (auth.currentSessionOrNull() == null) {
                                _sessionExpired.value = authenticatedSession.value != null
                                authenticatedSession.value = null
                                syncDao.clearSession()
                                initialized.complete(Unit)
                            }
                        }
                    }

                    // A transient refresh failure may still carry a locally restored SDK
                    // session. The Room mirror alone never grants access.
                    is SessionStatus.RefreshFailure -> {
                        AccountAccess.mutex.withLock { auth.currentSessionOrNull()?.let { persist(it) } }
                        initialized.complete(Unit)
                    }
                    else -> Unit
                }
            }
        }
    }

    /** Registers and signs in, unless the project requires email confirmation. */
    suspend fun signUp(email: String, password: String): Result<SignUpOutcome> =
        withContext(ioDispatcher) {
            AccountAccess.mutex.withLock { runCatching {
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
            } }
        }

    suspend fun signIn(email: String, password: String): Result<Unit> =
        withContext(ioDispatcher) {
            AccountAccess.mutex.withLock { runCatching {
                auth.signInWith(Email) {
                    this.email = email.trim()
                    this.password = password
                }
                persistCurrent()
            } }
        }

    suspend fun signOut() = AccountAccess.mutex.withLock {
        runCatching { auth.signOut(SignOutScope.GLOBAL) }
        // A failed network revocation must still remove the SDK's local credentials.
        auth.clearSession()
        withContext(ioDispatcher) { syncDao.clearSession(); authenticatedSession.value = null }
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
        AccountAccess.mutex.withLock { runCatching {
            val userId = auth.currentSessionOrNull()?.user?.id
                ?: error("Not signed in")
            remote.deleteAllEntries(userId)
            runCatching { auth.signOut(SignOutScope.GLOBAL) }
            auth.clearSession()
            syncDao.deleteAccountData(userId)
            authenticatedSession.value = null
            _sessionExpired.value = false
        } }
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
        if (auth.currentSessionOrNull()?.user?.id != user.id) return
        val previous = syncDao.currentSession()?.takeIf { it.userId == user.id }
        syncDao.replaceSession(
            SyncSessionEntity(
                userId = user.id,
                email = user.email,
                accessToken = null,
                refreshToken = null,
                lastPulledAt = previous?.lastPulledAt,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        authenticatedSession.value = syncDao.currentSession()
    }
}

/** Whether a registration completed with a session or needs confirmation. */
enum class SignUpOutcome {
    SignedIn,
    ConfirmEmailRequired,
}
