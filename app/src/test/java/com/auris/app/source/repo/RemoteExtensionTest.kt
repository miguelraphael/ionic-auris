package com.auris.app.source.repo

import com.auris.app.data.local.entity.ExtensionEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing tests for the repository index format.
 *
 * The index is an external contract with no compiler to check it against, so the field names
 * and shapes are asserted directly against a captured payload. If a repository ever changes
 * its format, these fail here rather than as an empty extensions list on device.
 */
class RemoteExtensionTest {

    // A trimmed capture of a real Aniyomi-format index. Field set taken from the live
    // repository rather than from the app's own model, so an unnoticed drift shows up.
    private val indexJson = """
        [
          {
            "name": "Aniyomi: AnimeOnsen",
            "pkg": "eu.kanade.tachiyomi.animeextension.all.animeonsen",
            "apk": "aniyomi-all.animeonsen-v14.10.apk",
            "lang": "all",
            "code": 10,
            "version": "14.10",
            "nsfw": 0,
            "sources": [
              {
                "name": "AnimeOnsen",
                "lang": "all",
                "id": "8542735178285060053",
                "baseUrl": "https://www.animeonsen.xyz"
              }
            ]
          },
          {
            "name": "Aniyomi: Adult Source",
            "pkg": "eu.kanade.tachiyomi.animeextension.en.adult",
            "apk": "aniyomi-en.adult-v2.apk",
            "lang": "en",
            "code": 2,
            "version": "2",
            "nsfw": 1,
            "torrent": 0,
            "sources": [
              { "name": "Adult", "lang": "en", "id": "1", "baseUrl": "https://x.test" },
              { "name": "Adult2", "lang": "en", "id": "2", "baseUrl": "https://y.test" }
            ]
          }
        ]
    """.trimIndent()

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun parsesEveryIndexField() {
        val index = json.decodeFromString<List<RemoteExtension>>(indexJson)

        assertEquals(2, index.size)

        val first = index[0]
        assertEquals("Aniyomi: AnimeOnsen", first.name)
        assertEquals("eu.kanade.tachiyomi.animeextension.all.animeonsen", first.packageName)
        assertEquals("aniyomi-all.animeonsen-v14.10.apk", first.apkFileName)
        assertEquals("all", first.lang)
        assertEquals(10L, first.versionCode)
        assertEquals("14.10", first.versionName)
        assertFalse(first.isNsfw)
        assertEquals(1, first.sourceCount)
    }

    @Test
    fun sourceIdsAreLongsNotStrings() {
        val index = json.decodeFromString<List<RemoteExtension>>(indexJson)

        // The index quotes `id` as a string, but it is a 64-bit value: parsing it as String
        // would silently truncate anything above Long.MAX and collide source ids.
        val id = index[0].sources!!.first().id
        assertEquals(8542735178285060053L, id)
    }

    @Test
    fun parsesSourceMetadata() {
        val source = json.decodeFromString<List<RemoteExtension>>(indexJson)[0].sources!!.first()

        assertEquals("AnimeOnsen", source.name)
        assertEquals("all", source.lang)
        assertEquals("https://www.animeonsen.xyz", source.baseUrl)
    }

    @Test
    fun readsNsfwFlag() {
        val index = json.decodeFromString<List<RemoteExtension>>(indexJson)

        assertFalse(index[0].isNsfw)
        assertTrue(index[1].isNsfw)
        assertEquals(2, index[1].sourceCount)
    }

    @Test
    fun toleratesUnknownAndMissingFields() {
        // Repositories add fields faster than apps ship. An unknown key must not fail the
        // whole index, and `torrent`/`nsfw` are optional in practice.
        val withExtras = """
            [{
              "name": "X", "pkg": "p", "apk": "a.apk", "lang": "en",
              "code": 1, "version": "1",
              "brandNewField": {"nested": [1, 2, 3]},
              "sig": "deadbeef"
            }]
        """.trimIndent()

        val parsed = json.decodeFromString<List<RemoteExtension>>(withExtras).single()

        assertEquals("X", parsed.name)
        assertEquals(0, parsed.sourceCount)
        assertNull(parsed.sources)
        assertFalse(parsed.isNsfw)
    }

    @Test
    fun apkAndIconUrlsAgreeWhicheverBaseFormIsUsed() {
        // The bug this guards: a user pastes the full index URL, and one URL builder kept the
        // `index.min.json` segment while the other stripped it, so APKs 404'd but icons loaded
        // (or the reverse).
        val extension = RemoteExtension(
            name = "X",
            packageName = "p",
            apkFileName = "x-v1.apk",
            lang = "en",
            versionCode = 1,
            versionName = "1",
        )
        val fromDir = ExtensionRepo("https://x.test/repo", "A")
        val fromIndex = ExtensionRepo("https://x.test/repo/index.min.json", "A")

        assertEquals(extension.apkUrl(fromDir), extension.apkUrl(fromIndex))
        assertEquals(
            ExtensionListItem(extension, fromDir, null).iconUrl,
            ExtensionListItem(extension, fromIndex, null).iconUrl,
        )
        assertEquals("https://x.test/repo/apk/x-v1.apk", extension.apkUrl(fromIndex))
    }

    @Test
    fun buildsApkUrlFromRepoBase() {
        val repo = ExtensionRepo(baseUrl = "https://example.test/repo", name = "Test")
        val extension = RemoteExtension(
            name = "X",
            packageName = "p",
            apkFileName = "x-v1.apk",
            lang = "en",
            versionCode = 1,
            versionName = "1",
        )

        assertEquals("https://example.test/repo/apk/x-v1.apk", extension.apkUrl(repo))
    }

    @Test
    fun buildsIconUrlFromRepoBase() {
        val repo = ExtensionRepo(baseUrl = "https://example.test/repo", name = "Test")
        val item = ExtensionListItem(
            remote = RemoteExtension(
                name = "X",
                packageName = "p",
                apkFileName = "x-v1.apk",
                lang = "en",
                versionCode = 1,
                versionName = "1",
            ),
            repo = repo,
            installed = null,
        )

        assertEquals("https://example.test/repo/icon/x-v1.apk", item.iconUrl)
    }

    // ---- Repo URL handling --------------------------------------------------

    @Test
    fun acceptsEitherIndexUrlOrDirectoryAsRepoBase() {
        // A user pasting the full index URL into Settings is the common case, so both forms
        // must resolve to the same fetch URL rather than producing `.../index.min.json/
        // index.min.json`.
        val withIndex = ExtensionRepo(baseUrl = "https://x.test/index.min.json", name = "A")
        val withDir = ExtensionRepo(baseUrl = "https://x.test", name = "A")
        val withSlash = ExtensionRepo(baseUrl = "https://x.test/", name = "A")

        assertEquals("https://x.test/index.min.json", withIndex.indexUrl)
        assertEquals("https://x.test/index.min.json", withDir.indexUrl)
        assertEquals("https://x.test/index.min.json", withSlash.indexUrl)
    }

    @Test
    fun defaultRepoIsHttpsAndPinned() {
        val repo = ExtensionRepo.DEFAULT

        assertTrue(
            "The default repo must be HTTPS",
            repo.baseUrl.startsWith("https://"),
        )
        assertEquals(repo.indexUrl, ExtensionRepo.DEFAULT.indexUrl)
    }

    // ---- Installed-state joining --------------------------------------------

    @Test
    fun comparesUpdatesByVersionCodeNotVersionName() {
        val repo = ExtensionRepo.DEFAULT
        val extension = RemoteExtension(
            name = "X",
            packageName = "p",
            apkFileName = "x.apk",
            lang = "en",
            versionCode = 10,
            versionName = "9",
        )

        val older = ExtensionListItem(extension, repo, installed(versionCode = 10))
        assertFalse(older.hasUpdate)

        // Same installed versionCode -> no update, even though the version *name* went
        // backwards. A string compare would offer a bogus "downgrade" here.
        assertFalse(ExtensionListItem(extension.copy(versionName = "9"), repo, older.installed).hasUpdate)

        val newer = ExtensionListItem(extension, repo, installed(versionCode = 9))
        assertTrue(newer.hasUpdate)

        val behind = ExtensionListItem(extension, repo, installed(versionCode = 11))
        assertFalse(behind.hasUpdate)
    }

    @Test
    fun exposesInstalledAndEnabledFlags() {
        val repo = ExtensionRepo.DEFAULT
        val extension = RemoteExtension(
            name = "X",
            packageName = "p",
            apkFileName = "x.apk",
            lang = "en",
            versionCode = 1,
            versionName = "1",
        )

        val none = ExtensionListItem(extension, repo, installed = null)
        assertFalse(none.isInstalled)
        assertFalse(none.isEnabled)
        assertFalse(none.hasUpdate)

        val disabled = ExtensionListItem(extension, repo, installed(enabled = false))
        assertTrue(disabled.isInstalled)
        assertFalse(disabled.isEnabled)

        val enabled = ExtensionListItem(extension, repo, installed(enabled = true))
        assertTrue(enabled.isInstalled)
        assertTrue(enabled.isEnabled)
    }

    private fun installed(versionCode: Long = 1, enabled: Boolean = true) = ExtensionEntity(
        packageName = "p",
        label = "X",
        versionName = "1",
        versionCode = versionCode,
        apkPath = "/tmp/x.apk",
        signatureHash = "sha",
        enabled = enabled,
    )

    // ---- Catalogue key --------------------------------------------------------

    @Test
    fun catalogueKeyJoinsPackageAndFirstSourceId() {
        val item = ExtensionListItem(
            remote = RemoteExtension(
                name = "Aniyomi: AnimeKhor",
                packageName = "animeextension.en.animekhor",
                apkFileName = "x.apk",
                lang = "en",
                versionCode = 30,
                versionName = "14.30",
                sources = listOf(
                    RemoteSource(id = 123456789L, lang = "en", name = "AnimeKhor"),
                ),
            ),
            repo = ExtensionRepo.DEFAULT,
            installed = null,
        )

        // Matches SourceResolver.keyOf's `package::id` shape, so the catalogue
        // route resolves back to the loaded source.
        assertEquals("animeextension.en.animekhor::123456789", item.catalogueKey)
    }

    @Test
    fun catalogueKeyIsNullWithoutSources() {        val item = ExtensionListItem(
            remote = RemoteExtension(
                name = "Helper",
                packageName = "p",
                apkFileName = "x.apk",
                lang = "en",
                versionCode = 1,
                versionName = "1",
                sources = null,
            ),
            repo = ExtensionRepo.DEFAULT,
            installed = null,
        )

        // A sourceless row must stay inert rather than open a dead catalogue.
        assertNull(item.catalogueKey)
    }

    @Test
    fun shortNameStripsRepoBrand() {
        val item = ExtensionListItem(
            remote = RemoteExtension(
                name = "Aniyomi: AnimeKhor",
                packageName = "p",
                apkFileName = "x.apk",
                lang = "en",
                versionCode = 1,
                versionName = "1",
            ),
            repo = ExtensionRepo.DEFAULT,
            installed = null,
        )

        assertEquals("AnimeKhor", item.shortName)
    }

    @Test
    fun shortNameKeepsUnbrandedNames() {
        val item = ExtensionListItem(
            remote = RemoteExtension(
                name = "Standalone",
                packageName = "p",
                apkFileName = "x.apk",
                lang = "en",
                versionCode = 1,
                versionName = "1",
            ),
            repo = ExtensionRepo.DEFAULT,
            installed = null,
        )

        assertEquals("Standalone", item.shortName)
    }
}