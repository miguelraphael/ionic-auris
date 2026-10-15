package com.auris.app.source.repo

import com.auris.app.data.local.entity.ExtensionEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One entry of an extension repository's `index.min.json`.
 *
 * Field names mirror Anikku's `ExtensionApi.ExtensionJsonObject` exactly. They are snake_case
 * and match the JSON, so `@SerialName` is only needed for `pkg`/`apk`, which are already
 * short names — kept explicit because the JSON contract is external and must not drift.
 */
@Serializable
data class RemoteExtension(
    @SerialName("name") val name: String,
    @SerialName("pkg") val packageName: String,
    @SerialName("apk") val apkFileName: String,
    @SerialName("lang") val lang: String,
    @SerialName("code") val versionCode: Long,
    @SerialName("version") val versionName: String,
    /** 0 or 1. Repositories use this to gate adult content. */
    @SerialName("nsfw") val nsfw: Int = 0,
    @SerialName("torrent") val torrent: Int = 0,
    /**
     * Null when the extension registers no source (a helper/utility extension). A repo entry
     * with no sources is still installable but offers nothing to browse, so the UI filters it.
     */
    @SerialName("sources") val sources: List<RemoteSource>? = null,
) {
    val isNsfw: Boolean get() = nsfw == 1
    val sourceCount: Int get() = sources?.size ?: 0

    /**
     * Full APK URL for a repository.
     *
     * Takes the [ExtensionRepo] rather than a raw base string so the directory is derived the
     * same way as for icons — see [ExtensionRepo.directoryUrl]. Passing the raw base here
     * would build `.../index.min.json/apk/x.apk` whenever a user pasted the full index URL.
     */
    fun apkUrl(repo: ExtensionRepo): String = "${repo.directoryUrl}/apk/$apkFileName"
}

/** A source advertised by an extension. */
@Serializable
data class RemoteSource(
    @SerialName("id") val id: Long,
    @SerialName("lang") val lang: String,
    @SerialName("name") val name: String,
    @SerialName("baseUrl") val baseUrl: String = "",
)

/**
 * An extension repository the user can browse.
 *
 * @param signingKeyFingerprint reserved for signature verification. Kept in the model so a
 *   repo can be added before verification is implemented, but **nothing is trusted yet** —
 *   see `ExtensionRepositoryClient`, which warns about this.
 */
data class ExtensionRepo(
    val baseUrl: String,
    val name: String,
    val shortName: String? = null,
    val website: String = "",
    val signingKeyFingerprint: String = "",
) {
    /**
     * The repository's directory, i.e. the base URL with any index filename removed.
     *
     * Users paste either `https://x.test/repo` or the full `https://x.test/repo/index.min.json`
     * into Settings, and both are live Aniyomi-format conventions. Everything that builds a
     * sub-resource URL — APKs, icons — goes through this so the two forms cannot produce
     * different results (`/repo/index.min.json/apk/x.apk` for one, `/repo/apk/x.apk` for the
     * other).
     */
    val directoryUrl: String
        get() = baseUrl
            .removeSuffix(INDEX_FILE)
            // `trimEnd` runs on both branches: removing `index.min.json` leaves the
            // separator behind, which would otherwise produce `.../repo//apk/x.apk`.
            .trimEnd('/')

    /** URL of the index itself. */
    val indexUrl: String get() = "$directoryUrl/$INDEX_FILE"

    companion object {
        /**
         * The index filename both Anikku and Aniyomi use.
         *
         * A `const`, so [directoryUrl] can strip it without allocating.
         */
        const val INDEX_FILE = "index.min.json"

        /**
         * Default repository.
         *
         * Modelled on Anikku's own default: a user-supplied `index.min.json` URL. Configurable
         * at runtime because these repos move, get taken down, or get replaced — a build-time
         * constant would strand every user when that happens.
         */
        val DEFAULT = ExtensionRepo(
            baseUrl = "https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json",
            name = "Auris Extensions",
            website = "https://github.com/yuzono/anime-repo",
        )
    }
}

/**
 * An extension offered by a repository, paired with its installed state.
 *
 * This is the unit the browse list renders: a single row carries both "what the repo says"
 * and "what this device has", so a row never has to join two sources to decide between
 * Install / Update / Remove.
 */
data class ExtensionListItem(
    val remote: RemoteExtension,
    val repo: ExtensionRepo,
    val installed: ExtensionEntity?,
) {
    val isInstalled: Boolean get() = installed != null

    val isEnabled: Boolean get() = installed?.enabled == true

    /**
     * Whether a newer build exists.
     *
     * Compares `versionCode`, never the version string: repository version names like
     * `14.15` are neither lexically nor numerically comparable.
     */
    val hasUpdate: Boolean
        get() = installed?.let { remote.versionCode > it.versionCode } ?: false

    /** Icon URL, following the repository's `icon/<apk>` convention. */
    val iconUrl: String
        get() = "${repo.directoryUrl}/icon/${remote.apkFileName}"

    /**
     * Catalogue route key for this extension's first advertised source.
     *
     * Null when the extension advertises no sources: such a row leads nowhere,
     * and its name stays inert rather than navigating to a dead catalogue.
     * Multi-source extensions browse their first source; single-source
     * extensions — the common anime case — are exact.
     */
    val catalogueKey: String?
        get() = remote.sources?.firstOrNull()?.let { "${remote.packageName}::${it.id}" }

    /**
     * Display name with the repository brand stripped.
     *
     * Index entries are branded — "Aniyomi: AnimeKhor" — and the brand is
     * identical across every row of a repo while eating a third of the title
     * width, so rows truncate to the same indistinguishable prefix. The repo
     * is already identified by the screen, making the prefix pure noise here.
     */
    val shortName: String
        get() = remote.name.substringAfter(": ", remote.name)
}

/** Stage of an in-flight install, keyed by package name in the UI state. */
data class InstallProgress(
    val packageName: String,
    val stage: Stage,
) {
    enum class Stage { DOWNLOADING, INSTALLING, DONE, FAILED }
}
