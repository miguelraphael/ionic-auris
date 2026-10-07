package com.auris.app.ui.extensions

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.auris.app.data.local.entity.ExtensionEntity
import com.auris.app.data.repository.ExtensionRepository
import com.auris.app.source.ExtensionApkReader
import com.auris.app.source.repo.ApkSigner
import com.auris.app.source.repo.SignatureVerdict
import com.auris.app.source.repo.SignatureVerifier
import com.auris.app.source.ExtensionLoader
import com.auris.app.source.repo.ExtensionListItem
import com.auris.app.source.repo.ExtensionRepo
import com.auris.app.source.repo.ExtensionRepositoryClient
import com.auris.app.source.repo.InstallProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

/** Callbacks the screen raises. */
data class ExtensionsUiActions(
    val onRefresh: () -> Unit = {},
    val onInstall: (ExtensionListItem) -> Unit = { },
    val onUninstall: (ExtensionListItem) -> Unit = { },
    val onSetEnabled: (ExtensionListItem, Boolean) -> Unit = { _, _ -> },
    val onToggleNsfw: (Boolean) -> Unit = { },
    val onEditRepo: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onFocus: (ExtensionListItem) -> Unit = { },
    val onDismissMessage: () -> Unit = {},
)

/** Everything the extensions screen renders. */
data class ExtensionsUiState(
    val items: List<ExtensionListItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val installed: Int = 0,
    val total: Int = 0,
    val updates: Int = 0,
    val showNsfw: Boolean = true,
    val repoUrl: String = ExtensionRepo.DEFAULT.baseUrl,
    /** Icon whose colours drive the screen's artwork gradient. */
    val focusIconKey: String? = null,
    val progress: Map<String, InstallProgress.Stage> = emptyMap(),
    val message: String? = null,
)

/**
 * State holder for the extensions screen.
 *
 * ### Local-first
 * Installed state always comes from Room, never from the repo index, so the installed list
 * stays correct offline and "installed but repo unreachable" is not an error state.
 *
 * ### Single writer
 * All install/uninstall/toggle logic lives here rather than in a second service, so there is
 * exactly one place that mutates either Room or the loader. Two writers previously meant the
 * progress map and the list could disagree.
 */
class ExtensionsViewModel(
    private val context: Context,
    private val client: ExtensionRepositoryClient,
    private val extensionRepository: ExtensionRepository,
    private val extensionLoader: ExtensionLoader,
    private val extensionsDir: File,
    defaultRepo: ExtensionRepo = ExtensionRepo.DEFAULT,
) : ViewModel() {

    private val repo = MutableStateFlow(defaultRepo)
    private val remoteIndex = MutableStateFlow<List<ExtensionListItem>>(emptyList())
    private val showNsfw = MutableStateFlow(true)
    private val focusIcon = MutableStateFlow<String?>(null)
    private val progress = MutableStateFlow<Map<String, InstallProgress.Stage>>(emptyMap())

    private val _state = MutableStateFlow(
        ExtensionsUiState(isLoading = true, repoUrl = defaultRepo.baseUrl),
    )
    val state: StateFlow<ExtensionsUiState> = _state.asStateFlow()

    init {
        refresh()

        viewModelScope.launch {
            // Room is the installed-state source of truth; the index only says what *could* be
            // installed, so a repo outage never makes an installed extension disappear.
            combine(
                extensionRepository.installed(),
                remoteIndex,
                repo,
                showNsfw,
            ) { installed, remote, currentRepo, includeNsfw ->
                val byPackage = installed.associateBy { it.packageName }
                val items = remote
                    .map { it.copy(installed = byPackage[it.remote.packageName]) }
                    .filter { includeNsfw || !it.remote.isNsfw }

                Quad(
                    items = items,
                    repo = currentRepo,
                    installedCount = installed.size,
                    total = remote.size,
                    updates = items.count { it.hasUpdate },
                )
            }.collect { snapshot ->
                _state.value = _state.value.copy(
                    items = snapshot.items,
                    installed = snapshot.installedCount,
                    total = snapshot.total,
                    updates = snapshot.updates,
                    repoUrl = snapshot.repo.baseUrl,
                    showNsfw = showNsfw.value,
                )
            }
        }
    }

    private data class Quad(
        val items: List<ExtensionListItem>,
        val repo: ExtensionRepo,
        val installedCount: Int,
        val total: Int,
        val updates: Int,
    )

    /** Re-reads the index. Safe to call repeatedly. */
    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)

            client.fetchIndex(repo.value)
                .onSuccess { index ->
                    remoteIndex.value = index
                        // A repo entry with no sources installs fine but offers nothing to
                        // browse, so it would be a row that leads nowhere.
                        .filter { it.sourceCount > 0 }
                        .map { ExtensionListItem(it, repo.value, null) }
                    _state.value = _state.value.copy(isLoading = false, error = null)
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = error.message ?: "Unknown error",
                    )
                }
        }
    }

    /**
     * Switches repository.
     *
     * A malformed URL is rejected here rather than at fetch time, so the error surfaces next
     * to the field the user typed it into.
     */
    fun setRepoUrl(url: String) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            _state.value = _state.value.copy(error = "Repository URL must start with http(s)://")
            return
        }
        repo.value = repo.value.copy(baseUrl = trimmed, name = trimmed.substringAfterLast('/'))
        refresh()
    }

    fun setNsfwVisible(visible: Boolean) {
        showNsfw.value = visible
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /**
     * Downloads, verifies and registers [item], then loads it.
     *
     * Order matters: the APK lands on a `.part` name first and is only moved into place after
     * its manifest has been parsed. A half-written file left in `extensionsDir` would be picked
     * up by the loader on next launch and fail as a corrupt extension rather than a failed
     * install.
     */
    fun install(item: ExtensionListItem) {
        viewModelScope.launch {
            val packageName = item.remote.packageName
            setStage(packageName, InstallProgress.Stage.DOWNLOADING)

            val target = File(extensionsDir, "$packageName.apk")
            val temp = File(extensionsDir, "$packageName.part")

            runCatching {
                client.downloadApk(item.remote, item.repo, temp).getOrThrow()

                setStage(packageName, InstallProgress.Stage.INSTALLING)

                // Identity comes from the APK, not the index: an index could claim one package
                // and ship another.
                val identity = ExtensionApkReader.read(context, temp)
                check(identity.packageName == packageName) {
                    "APK declares ${identity.packageName}, expected $packageName"
                }

                // Signature check, before the file is allowed to become the installed APK.
                //
                // Order matters: the APK is still a `.part` file here, so a rejection deletes it
                // and leaves the previous version installed. Verifying after the rename would
                // mean a hostile APK had already replaced a good one, and the failure path would
                // have to try to restore it.
                val fingerprint = verifySignature(item, temp)

                target.delete()
                check(temp.renameTo(target)) { "Could not move downloaded APK into place" }
                target.setReadOnly()

                extensionRepository.register(
                    ExtensionEntity(
                        packageName = identity.packageName,
                        label = item.remote.name,
                        versionName = identity.versionName,
                        versionCode = identity.versionCode,
                        apkPath = target.absolutePath,
                        signatureHash = identity.signatureHash,
                        signingCertFingerprint = fingerprint,
                        repoUrl = item.repo.baseUrl,
                    ),
                )

                extensionLoader.load(target, identity.packageName)
            }.onSuccess {
                _state.value = _state.value.copy(message = "Installed ${item.remote.name}")
            }.onFailure { error ->
                Log.w(TAG, "Install failed for $packageName", error)
                setStage(packageName, InstallProgress.Stage.FAILED)
                _state.value = _state.value.copy(message = "Install failed: ${error.message}")
                runCatching { temp.delete() }
            }
        }
    }

    /**
     * Checks [apkPath]'s signing certificate against the repository's claim and this extension's
     * existing pin, and returns the fingerprint to record.
     *
     * Throws when the APK must not be installed. [runCatching] in [install] turns that into a
     * failed install, and the `.part` file is deleted, so a rejected APK never reaches the
     * extensions directory and is never handed to the [extensionLoader].
     */
    private suspend fun verifySignature(item: ExtensionListItem, apkPath: File): String {
        val observed = ApkSigner.fingerprints(context.packageManager, apkPath.absolutePath)

        // Read *before* registering, so an update is checked against the pin from the install
        // it replaces rather than against itself.
        val pinned = extensionRepository.find(item.remote.packageName)?.signingCertFingerprint

        val verdict = SignatureVerifier.verify(
            declaredFingerprint = item.repo.signingKeyFingerprint,
            observed = observed,
            previouslyPinned = pinned,
        )

        return when (verdict) {
            is SignatureVerdict.Trusted -> {
                // Prefer the pinned value so an update that legitimately adds a second signer
                // does not silently re-pin to a different key.
                val chosen = pinned
                    ?.takeIf { SignatureVerifier.normalize(it) != null }
                    ?: observed.first()
                Log.i(TAG, "Signature trusted for ${item.remote.packageName} (${chosen.take(12)}…)")
                chosen
            }

            is SignatureVerdict.Pinned -> {
                // Trust on first use. Allowed so a repository with no declared key is usable at
                // all, but reported so the UI can say it is pinned rather than verified.
                Log.w(
                    TAG,
                    "No declared signing key for ${item.remote.packageName}; " +
                        "pinning ${verdict.fingerprint.take(12)}… on first install",
                )
                verdict.fingerprint
            }

            is SignatureVerdict.Rejected -> {
                Log.w(TAG, "Signature rejected for ${item.remote.packageName}: ${verdict.reason}")
                error(verdict.reason)
            }
        }
    }

    /** Uninstalls and drops the APK so a stale file cannot be loaded next launch. */
    fun uninstall(item: ExtensionListItem) {
        viewModelScope.launch {
            extensionRepository.uninstall(item.remote.packageName)
            runCatching { File(extensionsDir, "${item.remote.packageName}.apk").delete() }
            extensionLoader.clearCache()
            _state.value = _state.value.copy(message = "Removed ${item.remote.name}")
        }
    }

    fun setEnabled(item: ExtensionListItem, enabled: Boolean) {
        viewModelScope.launch { extensionRepository.setEnabled(item.remote.packageName, enabled) }
    }

    fun focus(item: ExtensionListItem) {
        focusIcon.value = item.iconUrl
        _state.value = _state.value.copy(focusIconKey = item.iconUrl)
    }

    private fun setStage(packageName: String, stage: InstallProgress.Stage) {
        progress.value = progress.value + (packageName to stage)
        _state.value = _state.value.copy(progress = progress.value)
    }

    companion object {
        private const val TAG = "AurisExtensions"
    }

    /** Factory: avoids a DI framework for one screen. */
    class Factory(
        private val context: Context,
        private val client: ExtensionRepositoryClient,
        private val extensionRepository: ExtensionRepository,
        private val extensionLoader: ExtensionLoader,
        private val extensionsDir: File,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ExtensionsViewModel(
                context,
                client,
                extensionRepository,
                extensionLoader,
                extensionsDir,
            ) as T
    }
}

/** Actions the screen binds to a view model's command surface. */
fun ExtensionsViewModel.actions(): ExtensionsUiActions = ExtensionsUiActions(
    onRefresh = ::refresh,
    onInstall = ::install,
    onUninstall = ::uninstall,
    onSetEnabled = ::setEnabled,
    onToggleNsfw = ::setNsfwVisible,
    onEditRepo = {},
    onRetry = ::refresh,
    onFocus = ::focus,
    onDismissMessage = ::consumeMessage,
)
