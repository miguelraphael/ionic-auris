package com.auris.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.auris.app.source.repo.ExtensionRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's chosen extension repository, persisted.
 *
 * ### Why this exists
 * Both the repository URL and its **signing key fingerprint** are trust decisions. Two problems
 * made them unusable before this existed:
 *
 * - **They were not shared.** Settings and Extensions each held their own `StateFlow<ExtensionRepo>`,
 *   so a fingerprint entered in Settings never reached the code that verifies installs. The field
 *   would have looked like it worked while changing nothing.
 * - **They were not persisted.** Both lived only in a ViewModel, so both were forgotten on
 *   process death — meaning every extension would silently fall back to being pinned rather than
 *   verified at the next launch, with no explanation.
 *
 * A trust decision that evaporates on restart is worse than no decision: the user believes they
 * pinned a repository and the app quietly reverted to trust-on-first-use.
 *
 * ### Why SharedPreferences rather than DataStore
 * This is four scalars read once at startup and written on an explicit user action. DataStore
 * would add a dependency and a coroutine to save nothing; Preferences is already on the platform
 * and the data is small enough that a synchronous read in `AppContainer` is fine.
 */
class ExtensionRepoStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _repo = MutableStateFlow(read())

    /** The current repository. Emits on change so every screen stays in step. */
    val repo: StateFlow<ExtensionRepo> = _repo.asStateFlow()

    /** The value as of now, for callers that are not observing. */
    fun current(): ExtensionRepo = _repo.value

    /**
     * Saves [repo] and notifies observers.
     *
     * The URL is stored normalised to the index URL, and read back through
     * [ExtensionRepo.directoryUrl]'s inverse, so a user pasting either
     * `https://x/repo` or `https://x/repo/index.min.json` ends up with one stored form.
     */
    fun save(repo: ExtensionRepo) {
        prefs.edit()
            .putString(KEY_BASE_URL, repo.baseUrl)
            .putString(KEY_NAME, repo.name)
            .putString(KEY_FINGERPRINT, repo.signingKeyFingerprint)
            .putString(KEY_WEBSITE, repo.website)
            .apply()

        _repo.value = repo
    }

    /** Convenience for the common case of changing one field. */
    fun update(transform: (ExtensionRepo) -> ExtensionRepo) = save(transform(_repo.value))

    private fun read(): ExtensionRepo {
        val baseUrl = prefs.getString(KEY_BASE_URL, null)
            ?: return ExtensionRepo.DEFAULT

        return ExtensionRepo(
            baseUrl = baseUrl,
            name = prefs.getString(KEY_NAME, null) ?: baseUrl.substringAfterLast('/'),
            website = prefs.getString(KEY_WEBSITE, "").orEmpty(),
            signingKeyFingerprint = prefs.getString(KEY_FINGERPRINT, "").orEmpty(),
        )
    }

    /** Restores the built-in default. Used by "forget this repository". */
    fun reset() {
        prefs.edit().clear().apply()
        _repo.value = ExtensionRepo.DEFAULT
    }

    private companion object {
        const val PREFS_NAME = "auris_extension_repo"
        const val KEY_BASE_URL = "base_url"
        const val KEY_NAME = "name"
        const val KEY_FINGERPRINT = "signing_key_fingerprint"
        const val KEY_WEBSITE = "website"
    }
}
