package com.auris.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.auris.app.data.local.entity.ExtensionEntity
import com.auris.app.data.repository.AuthRepository
import com.auris.app.data.repository.ExtensionRepoStore
import com.auris.app.data.repository.ExtensionRepository
import com.auris.app.source.repo.ExtensionRepo
import com.auris.app.source.repo.SignatureVerifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * How much is actually known about who signed an installed extension.
 *
 * ### Why the wording is so careful
 * "Verified" and "trusted" are not interchangeable, and collapsing them would overstate what the
 * app knows:
 * - [Verified] means the repository publishes a key and the APK matches it. Strong.
 * - [Pinned] means the repository published no key, so the first fingerprint seen was recorded.
     That protects against a later swap but says nothing about the repo's honesty at install
     time, so it must not be presented as verified.
 * - [Unrecorded] means the extension predates signature checking. Nothing is claimed.
 */
enum class ExtensionTrust {
    VERIFIED,
    PINNED,
    UNRECORDED,
}

/** One installed extension, as the settings screen describes it. */
data class ExtensionSecurity(
    val packageName: String,
    val label: String,
    val trust: ExtensionTrust,
    /** Shortened for display; the full value is never needed on screen. */
    val fingerprintShort: String?,
)

data class SettingsUiState(
    val repoUrl: String = ExtensionRepo.DEFAULT.baseUrl,
    val repoName: String = ExtensionRepo.DEFAULT.name,
    val repoEditing: Boolean = false,
    /** Null when the repository publishes no signing key. */
    val repoDeclaredKey: String? = null,
    /** Null when signed out; the auth gate owns that case, not this screen. */
    val accountEmail: String? = null,
    val extensions: List<ExtensionSecurity> = emptyList(),
    val verifiedCount: Int = 0,
    val pinnedCount: Int = 0,
    val unrecordedCount: Int = 0,
    val message: String? = null,
    /** Guards the irreversible delete against double-taps. */
    val isDeletingAccount: Boolean = false,
) {
    /** True when nothing at all has been pinned yet, so the screen can explain rather than list. */
    val isEmpty: Boolean get() = extensions.isEmpty()

    /**
     * Whether the repository offers no signature guarantee at all.
     *
     * Called out explicitly rather than left implicit, because it is the difference between
     * "extensions are checked" and "extensions are merely hashed".
     */
    val repoHasNoKey: Boolean get() = !SignatureVerifier.isWellFormed(repoDeclaredKey)
}

class SettingsViewModel(
    private val extensionRepository: ExtensionRepository,
    private val repoStore: ExtensionRepoStore,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)
    private val _state = MutableStateFlow(
        SettingsUiState(
            repoUrl = repoStore.current().baseUrl,
            repoName = repoStore.current().name,
            repoDeclaredKey = repoStore.current().signingKeyFingerprint,
        ),
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        // The store is the single source of truth for the repository, shared with the
        // extensions screen. Observed rather than copied, so a fingerprint entered here is the
        // same value the install path verifies against — not a copy that can drift.
        repoStore.repo
            .onEach { publish(extensionRepository.installedSnapshot()) }
            .launchIn(viewModelScope)

        extensionRepository.installed()
            .onEach { installed -> publish(installed) }
            .launchIn(viewModelScope)

        // The gate owns the signed-out case; here the email is display-only,
        // and sign-out routes back to the gate through the same flow.
        authRepository.session
            .onEach { session ->
                _state.value = _state.value.copy(accountEmail = session?.email)
            }
            .launchIn(viewModelScope)
    }

    private fun publish(installed: List<ExtensionEntity>) {
        val repo = repoStore.current()
        val declared = SignatureVerifier.normalize(repo.signingKeyFingerprint)

        val security = installed.map { extension ->
            val fingerprint = SignatureVerifier.normalize(extension.signingCertFingerprint)

            ExtensionSecurity(
                packageName = extension.packageName,
                label = extension.label,
                trust = when {
                    // No fingerprint recorded: installed before verification existed.
                    fingerprint == null -> ExtensionTrust.UNRECORDED

                    // The repository's published key matches what was pinned at install.
                    declared != null && declared == fingerprint -> ExtensionTrust.VERIFIED

                    // Pinned on first install, or the repo has since published no key.
                    else -> ExtensionTrust.PINNED
                },
                fingerprintShort = fingerprint?.let { "${it.take(12)}…" },
            )
        }

        _state.value = _state.value.copy(
            repoUrl = repo.baseUrl,
            repoName = repo.name,
            repoDeclaredKey = declared,
            extensions = security,
            verifiedCount = security.count { it.trust == ExtensionTrust.VERIFIED },
            pinnedCount = security.count { it.trust == ExtensionTrust.PINNED },
            unrecordedCount = security.count { it.trust == ExtensionTrust.UNRECORDED },
            message = message.value,
        )
    }

    fun beginRepoEdit() {
        _state.value = _state.value.copy(repoEditing = true)
    }

    fun cancelRepoEdit() {
        _state.value = _state.value.copy(repoEditing = false)
    }

    /**
     * Sets the repository, rejecting a URL that cannot be checked.
     *
     * ### Why plain HTTP is refused outright
     * The repository index decides which APKs are offered, and each APK runs with the app's
     * full permissions once loaded. Over plain HTTP an attacker on the network path can rewrite
     * the index to point at an APK they control. Certificate pinning on the APK does not save
     * that case, because the attacker simply declares their own key in the rewritten index — the
     * user would see a "verified" extension signed by nobody they know.
     *
     * So the transport is what the user is asked to trust first, and it is refused rather than
     * warned about. Everything else about the extension is then checked cryptographically.
     */
    fun setRepoUrl(url: String) {
        viewModelScope.launch {
            applyRepoUrl(url)
        }
    }

    /** Suspending body of [setRepoUrl]; kept separate because the last step reads the DB. */
    private suspend fun applyRepoUrl(url: String) {
        val trimmed = url.trim()

        when {
            trimmed.isEmpty() -> {
                _state.value = _state.value.copy(
                    repoEditing = true,
                    message = "Repository URL cannot be empty",
                )
            }

            !trimmed.startsWith("https://") -> {
                _state.value = _state.value.copy(
                    repoEditing = true,
                    message = "Repository URL must use https:// — an unencrypted index can be " +
                        "rewritten to serve an APK signed by an attacker's own key",
                )
            }

            else -> {
                repoStore.update { repo ->
                    repo.copy(
                        baseUrl = trimmed,
                        name = trimmed.substringAfterLast('/').ifBlank { "Custom repository" },
                    )
                }
                _state.value = _state.value.copy(
                    repoEditing = false,
                    message = "Repository set. Extension lists refresh from it.",
                )
                // Trust is recomputed by the store observer above, because a different
                // repository may publish a different key and every verdict depends on it.
            }
        }
    }

    /**
     * Validates and saves the repository's signing key fingerprint.
     *
     * ### Why a bad value is refused rather than saved
     * A fingerprint that is not 64 hex characters can never match an APK, and the verifier's
     * policy is to **reject installs** when a declared key is malformed — so saving garbage would
     * turn every extension from this repository into a hard install failure. Failing here, next
     * to the field, is the only place the user can see the reason.
     *
     * An empty value is allowed and meaningful: it declares "this repository publishes no key",
     * which is the state every non-Anikku repository is in, and it downgrades verification to
     * pinning rather than breaking installs.
     */
    fun setSigningKeyFingerprint(value: String) {
        val trimmed = value.trim()

        if (trimmed.isEmpty()) {
            repoStore.update { it.copy(signingKeyFingerprint = "") }
            _state.value = _state.value.copy(
                message = "Signing key cleared. Extensions from this repository will be pinned, " +
                    "not verified.",
            )
            return
        }

        if (!SignatureVerifier.isWellFormed(trimmed)) {
            _state.value = _state.value.copy(
                message = "A fingerprint is 64 hexadecimal characters. Copy it with the colons " +
                    "intact — both forms are accepted.",
            )
            return
        }

        repoStore.update { it.copy(signingKeyFingerprint = trimmed) }
        _state.value = _state.value.copy(
            message = "Signing key saved. Extensions will be checked against it.",
        )
    }

    /** Forgets the custom repository and returns to the built-in default. */
    fun resetRepo() {        repoStore.reset()
        _state.value = _state.value.copy(
            repoEditing = false,
            message = "Repository reset to the default.",
        )
    }

    fun consumeMessage() {
        message.value = null
        _state.value = _state.value.copy(message = null)
    }

    /**
     * Signs out and returns to the login gate.
     *
     * No navigation here: clearing the session flips the gate flow, which
     * routes on its own. The library rows stay on device; signing back in as
     * the same user re-syncs them rather than duplicating.
     */
    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }

    /**
     * Deletes the account's server library, signs out, and returns to the
     * login gate through the session flow — same routing as sign-out, no
     * navigation here. Failures surface as a message and keep the session,
     * so a half-deleted account is impossible from the user's side.
     */
    fun deleteAccount() {
        if (_state.value.isDeletingAccount) return
        _state.value = _state.value.copy(isDeletingAccount = true, message = null)
        viewModelScope.launch {
            authRepository.deleteAccount()
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        isDeletingAccount = false,
                        message = error.message?.takeIf { it.isNotBlank() }
                            ?: "Could not delete the account. Check your connection and try again.",
                    )
                }
            // Success clears the session, which drops this screen for the gate;
            // the flag intentionally stays on so nothing else can fire mid-handover.
        }
    }

    class Factory(
        private val extensionRepository: ExtensionRepository,
        private val repoStore: ExtensionRepoStore,
        private val authRepository: AuthRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(extensionRepository, repoStore, authRepository) as T
    }

    companion object {
        fun factory(
            extensionRepository: ExtensionRepository,
            repoStore: ExtensionRepoStore,
            authRepository: AuthRepository,
        ): ViewModelProvider.Factory = Factory(extensionRepository, repoStore, authRepository)
    }
}

/**
 * A one-shot read of the installed set.
 *
 * Needed because [setRepoUrl] has to recompute trust synchronously, inside a click handler, and
 * the observed [kotlinx.coroutines.flow.Flow] has no blocking getter. Suspending on a first
 * emission is the alternative; this is simpler and the list is already in memory.
 */
internal suspend fun ExtensionRepository.installedSnapshot(): List<ExtensionEntity> =
    installed().first()
