package com.auris.app.ui.extensions

import com.auris.app.source.repo.ExtensionListItem
import com.auris.app.source.repo.ExtensionRepo
import com.auris.app.source.repo.RemoteExtension
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Filter rules for the extensions list.
 *
 * The list holds 250+ rows, so search correctness is asserted here rather than by
 * scrolling on device. The ViewModel applies [matchesListFilter] to every row;
 * pagination (`visibleCount`) only windows the survivors and needs no logic test.
 */
class ExtensionFilterTest {

    private val repo = ExtensionRepo.DEFAULT

    private fun item(
        name: String = "Aniyomi: AnimeKhor",
        packageName: String = "animeextension.en.animekhor",
        lang: String = "en",
        nsfw: Int = 0,
    ) = ExtensionListItem(
        remote = RemoteExtension(
            name = name,
            packageName = packageName,
            apkFileName = "x.apk",
            lang = lang,
            versionCode = 1,
            versionName = "1",
            nsfw = nsfw,
        ),
        repo = repo,
        installed = null,
    )

    @Test
    fun blankQueryKeepsEverything() {
        assertTrue(item().matchesListFilter("", includeNsfw = true))
        assertTrue(item().matchesListFilter("   ", includeNsfw = true))
    }

    @Test
    fun matchesByNameCaseInsensitively() {
        assertTrue(item().matchesListFilter("animekhor", includeNsfw = true))
        assertTrue(item().matchesListFilter("ANIMEKHOR", includeNsfw = true))
        assertFalse(item().matchesListFilter("animetake", includeNsfw = true))
    }

    @Test
    fun matchesByPackageName() {
        // Users paste package fragments from issue reports; the display name alone
        // would miss "animeextension.en.animekhor" for query "animekhor" only if the
        // name did not contain it — package matching covers renamed extensions.
        val renamed = item(name = "Something Else Entirely")
        assertTrue(renamed.matchesListFilter("animekhor", includeNsfw = true))
        assertFalse(renamed.matchesListFilter("gogoanime", includeNsfw = true))
    }

    @Test
    fun matchesByLanguage() {
        assertTrue(item().matchesListFilter("en", includeNsfw = true))
        assertFalse(item().matchesListFilter("ar", includeNsfw = true))
    }

    @Test
    fun nsfwToggleHidesAdultContentRegardlessOfQuery() {
        val adult = item(name = "Adult", nsfw = 1)
        assertFalse(adult.matchesListFilter("", includeNsfw = false))
        assertFalse(adult.matchesListFilter("adult", includeNsfw = false))
        assertTrue(adult.matchesListFilter("adult", includeNsfw = true))
    }

    @Test
    fun surroundingWhitespaceIsIgnored() {
        assertTrue(item().matchesListFilter("  animekhor  ", includeNsfw = true))
    }
}
