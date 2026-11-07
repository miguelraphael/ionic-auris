package com.auris.app.ui.music

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auris.app.MainActivity
import com.auris.app.ui.theme.AurisTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real dialog windows: catch controls clipped by decor/system navigation on a device. */
@RunWith(AndroidJUnit4::class)
class MusicPageLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val song = MusicRow(MusicRow.Kind.TRACK, "A long song title for layout verification", "Artist", null, "test-song")

    @Test fun albumPlayerFitsInsideWindow() = checkPage("album") { mini ->
        MusicAlbumPage("Album", List(12) { song.copy(id = "song-$it") }, false, null,
            {}, {}, {}, {}, bottomBar = mini)
    }

    @Test fun artistPlayerFitsInsideWindow() = checkPage("artist") { mini ->
        MusicArtistPage("Artist", null, "An artist biography.", listOf(song), false, null,
            {}, {}, {}, {}, {}, bottomBar = mini)
    }

    private fun checkPage(name: String, content: @Composable (@Composable () -> Unit) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                activity.setContent { AurisTheme { content {
                    NowPlayingStrip(title = song.title, artist = song.subtitle, artworkUrl = null,
                        isPlaying = false, onClick = {}, onTogglePlayPause = {},
                        onPrevious = {}, onNext = {}, canGoPrevious = true, canGoNext = true)
                } } }
            }
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
            val deadline = SystemClock.uptimeMillis() + 15_000
            var next: AccessibilityNodeInfo? = null
            while (next == null && SystemClock.uptimeMillis() < deadline) {
                next = find(instrumentation.uiAutomation.rootInActiveWindow, "Next track")
                if (next == null) SystemClock.sleep(100)
            }
            assertNotNull("Mini-player did not appear on $name page", next)
            for (label in listOf("Previous track", "Play", "Next track")) {
                var button = find(instrumentation.uiAutomation.rootInActiveWindow, label)
                    ?: error("Missing control: $label")
                while (!button.isClickable && button.parent != null) button = button.parent
                val bounds = Rect().also(button::getBoundsInScreen)
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                assertTrue("$label extends below screen: $bounds", bounds.bottom <= bitmap.height)
                assertTrue("$label extends outside screen: $bounds", bounds.left >= 0 && bounds.right <= bitmap.width)
                val density = instrumentation.targetContext.resources.displayMetrics.density
                assertTrue("$label is vertically clipped: $bounds", bounds.height() >= (44 * density).toInt())
                bitmap.recycle()
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(800)
            val power = instrumentation.targetContext.getSystemService(android.os.PowerManager::class.java)
            assertTrue("Screen must be awake for visual verification", power.isInteractive)
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            File(instrumentation.targetContext.cacheDir, "$name-mini-player.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    private fun find(root: AccessibilityNodeInfo?, description: String): AccessibilityNodeInfo? {
        root ?: return null
        if (root.contentDescription?.toString() == description) return root
        for (i in 0 until root.childCount) find(root.getChild(i), description)?.let { return it }
        return null
    }
}
