package com.auris.app.ui.music

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auris.app.AurisApplication
import com.auris.app.MainActivity
import com.auris.app.data.remote.music.*
import com.auris.app.ui.navigation.AurisNavHost
import com.auris.app.ui.navigation.Routes
import com.auris.app.ui.theme.AurisTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production NavHost and collection dialogs without external catalogue/audio calls. */
@RunWith(AndroidJUnit4::class)
class MusicBackStackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val artist = MusicArtistDetails("fixture-artist", "Navigation Artist")
    private val album = MusicAlbum("fixture-album", "Navigation Album", artist.name, null, null, 1)
    private val track = MusicTrack("fixture-song", "Navigation Song", artist.name, album.title,
        180_000, null, null, null, null)
    private val source = object : MusicSource {
        override val discoverySeeds = emptyList<String>()
        override suspend fun search(query: String, limit: Int) = Result.success(listOf(
            MusicCollectionItem(MusicCollectionItem.Kind.ARTIST, artist = artist)))
        override suspend fun collection(browseId: String, limit: Int) = Result.success(
            if (browseId == artist.id) listOf(MusicCollectionItem(MusicCollectionItem.Kind.ALBUM, album = album))
            else listOf(MusicCollectionItem(MusicCollectionItem.Kind.TRACK, tracks = listOf(track))))
        override suspend fun artistDetails(artistId: String, defaultName: String) = Result.success(artist)
        override suspend fun resolveAudioStream(videoId: String): Result<ResolvedAudio> =
            Result.failure(IllegalStateException("Navigation-only fixture"))
    }

    private fun matching(label: String): List<AccessibilityNodeInfo> {
        fun collect(n: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
            n ?: return emptyList()
            return (if (!n.isEditable && n.text?.toString() == label) listOf(n) else emptyList()) +
                (0 until n.childCount).flatMap { collect(n.getChild(it)) }
        }
        return collect(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 12_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        if (!condition()) {
            val image = instrumentation.uiAutomation.takeScreenshot()
            java.io.File(instrumentation.targetContext.cacheDir, "music-navigation-failure.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
            fail("Expected navigation state was not reached")
        }
    }
    private fun click(label: String) {
        waitFor { matching(label).isNotEmpty() }
        var node = matching(label).first()
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("Cannot click $label", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
    }

    @Test fun albumMiniPlayerOpensDirectlyAndGestureBackRestoresAlbumThenArtist() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var nav: NavHostController
            lateinit var music: MusicBrowseViewModel
            scenario.onActivity { activity ->
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                val c = (activity.application as AurisApplication).container
                activity.setContent {
                    AurisTheme {
                        nav = rememberNavController()
                        AurisNavHost(navController = nav, library = emptyList(), onEntryClick = {},
                            libraryRepository = c.libraryRepository, kitsuIdentifier = c.kitsuIdentifier,
                            extensionContentRepository = c.extensionContentRepository,
                            extensionRepository = c.extensionRepository, extensionRepoStore = c.extensionRepoStore,
                            extensionLoader = c.extensionLoader, authRepository = c.authRepository,
                            musicSource = source)
                        LaunchedEffect(Unit) { nav.navigate(Routes.MUSIC_BROWSE) }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(1200)
            scenario.onActivity {
                music = ViewModelProvider(it).get("music", MusicBrowseViewModel::class.java)
                music.search("Navigation Artist")
            }
            click(artist.name)
            click(album.title)
            click(track.title)
            // Resolution is intentionally refused; metadata still exposes the actual mini-player path.
            waitFor { !music.state.value.isResolving && music.state.value.nowPlayingId == track.videoId }
            fun mini(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                n ?: return null
                if (n.contentDescription?.toString()?.contains("Open now playing") == true) return n
                for (i in 0 until n.childCount) mini(n.getChild(i))?.let { return it }
                return null
            }
            waitFor { mini(instrumentation.uiAutomation.rootInActiveWindow) != null }
            val bounds = android.graphics.Rect().also {
                mini(instrumentation.uiAutomation.rootInActiveWindow)!!.getBoundsInScreen(it)
            }
            val tap = instrumentation.uiAutomation.executeShellCommand(
                "input tap ${bounds.left + bounds.width() / 4} ${bounds.centerY()}")
            java.io.FileInputStream(tap.fileDescriptor).use { it.readBytes() }
            tap.close()
            waitFor { nav.currentDestination?.route == Routes.PLAYER }
            SystemClock.sleep(600)
            assertTrue("Artist/album dialog still covers player", matching(album.title).isEmpty())
            val gesture = instrumentation.uiAutomation.executeShellCommand("input swipe 0 900 450 900 300")
            java.io.FileInputStream(gesture.fileDescriptor).use { it.readBytes() }
            gesture.close()
            waitFor { nav.currentDestination?.route == Routes.MUSIC_BROWSE }
            waitFor { matching(album.title).isNotEmpty() && matching(track.title).isNotEmpty() }
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            waitFor { matching(artist.name).isNotEmpty() && matching(album.title).isNotEmpty() }
        }
    }
}
