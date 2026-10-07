package com.auris.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.auris.app.data.local.entity.ExtensionEntity
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
    val extensions: List<ExtensionSecurity> = emptyList(),
    val verifiedCount: Int = 0,
    val pinnedCount: Int = 0,
    val unrecordedCount: Int = 0,
    val message: String? = null,
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
    private val initialRepo: ExtensionRepo,
) : ViewModel() {

    private val repo = MutableStateFlow(initialRepo)
    private val message = MutableStateFlow<String?>(null)
    private val _state = MutableStateFlow(
        SettingsUiState(
            repoUrl = initialRepo.baseUrl,
            repoName = initialRepo.name,
            repoDeclaredKey = initialRepo.signingKeyFingerprint,
        ),
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        extensionRepository.installed()
            .onEach { installed -> publish(installed) }
            .launchIn(viewModelScope)
    }

    private fun publish(installed: List<ExtensionEntity>) {
        val declared = SignatureVerifier.normalize(repo.value.signingKeyFingerprint)

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
            repoUrl = repo.value.baseUrl,
            repoName = repo.value.name,
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
                repo.value = repo.value.copy(
                    baseUrl = trimmed,
                    name = trimmed.substringAfterLast('/').ifBlank { "Custom repository" },
                )
                _state.value = _state.value.copy(
                    repoEditing = false,
                    message = "Repository set. Extension lists refresh from it.",
                )
                // Recompute trust, because a different repository may publish a different key
                // and the per-extension verdict depends on it.
                publish(extensionRepository.installedSnapshot())
            }
        }
    }

    fun consumeMessage() {
        message.value = null
        _state.value = _state.value.copy(message = null)
    }

    class Factory(
        private val extensionRepository: ExtensionRepository,
        private val repo: ExtensionRepo,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(extensionRepository, repo) as T
    }

    companion object {
        fun factory(
            extensionRepository: ExtensionRepository,
            repo: ExtensionRepo = ExtensionRepo.DEFAULT,
        ): ViewModelProvider.Factory = Factory(extensionRepository, repo)
    }
}

/**
 * A one-shot read of the installed set.
 *
 * Needed because [setRepoUrl] has to recompute trust synchronously, inside a click handler, and
 * the observed [kotlinx.coroutines.flow.Flow] has no blocking getter. Suspending on a first
 * emission is the alternative; this is simpler and the list is already in memory.
 */
private suspend fun ExtensionRepository.installedSnapshot(): List<ExtensionEntity> =
    installed().first()
