package com.auris.app.ui.music

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auris.app.MainActivity
import com.auris.app.core.model.MediaType
import com.auris.app.ui.home.HomeScreen
import com.auris.app.ui.source.CatalogueScreen
import com.auris.app.ui.source.CatalogueUiState
import com.auris.app.ui.theme.AurisTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProductClarityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Test fun homeActionsAndCatalogueHeadings() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun show(content: @Composable () -> Unit) {
                scenario.onActivity { it.setShowWhenLocked(true); it.setTurnScreenOn(true); it.setContent { AurisTheme { content() } } }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(1000)
            }
            fun capture(name: String) {
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                File(instrumentation.targetContext.cacheDir, "$name.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            var chosen: MediaType? = null
            show { HomeScreen(emptyList(), {}, {}, { chosen = it }) }
            capture("home-before")
            for ((label, type) in listOf("Watch anime" to MediaType.ANIME,
                "Listen to music" to MediaType.MUSIC, "Read manga" to MediaType.MANGA)) {
                fun find(n: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                    n ?: return null
                    if (n.text?.toString() == label) return n
                    for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
                    return null
                }
                val deadline = SystemClock.uptimeMillis() + 15_000
                var found = find(instrumentation.uiAutomation.rootInActiveWindow)
                while (found == null && SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(200)
                    found = find(instrumentation.uiAutomation.rootInActiveWindow)
                }
                var node = found ?: error("Missing $label")
                while (!node.isClickable && node.parent != null) node = node.parent
                assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                assertEquals(type, chosen)
            }
            capture("home-entry")
            val playing = androidx.compose.runtime.mutableStateOf(false)
            show {
                com.auris.app.ui.player.MusicPlayerScreen(
                    state = com.auris.app.ui.player.PlayerUiState(
                        title = "Auris visual verification", artist = "A deliberately long artist name with several collaborators",
                        album = "A long album name that must remain readable independently of its artist", queueSize = 2, durationMs = 180_000,
                        isPlaying = playing.value,
                    ),
                    onPlayPause = { playing.value = !playing.value }, onSeekFraction = {}, onVolumeChange = {},
                    onCycleRepeat = {}, onToggleShuffle = {},
                )
            }
            fun control(n: android.view.accessibility.AccessibilityNodeInfo?, label: String, clickable: Boolean = true): android.view.accessibility.AccessibilityNodeInfo? {
                n ?: return null
                if (n.contentDescription?.toString()?.contains(label) == true && (!clickable || n.isClickable)) return n
                for (i in 0 until n.childCount) control(n.getChild(i), label, clickable)?.let { return it }
                return null
            }
            fun scrollable(n: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                n ?: return null
                if (n.isScrollable) return n
                for (i in 0 until n.childCount) scrollable(n.getChild(i))?.let { return it }
                return null
            }
            fun artworkBounds(): android.graphics.Rect {
                val deadline = SystemClock.uptimeMillis() + 12_000
                var node = control(instrumentation.uiAutomation.rootInActiveWindow, "Album artwork", false)
                while (node == null && SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(100)
                    node = control(instrumentation.uiAutomation.rootInActiveWindow, "Album artwork", false)
                }
                return android.graphics.Rect().also { (node ?: error("Artwork did not appear")).getBoundsInScreen(it) }
            }
            val artBefore = artworkBounds()
            capture("music-player")
            scrollable(instrumentation.uiAutomation.rootInActiveWindow)
                ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(400)
            val artAfter = artworkBounds()
            assertEquals("Artwork must stay fixed while details scroll", artBefore, artAfter)
            val screen = instrumentation.uiAutomation.takeScreenshot()
            val density = instrumentation.targetContext.resources.displayMetrics.density
            val bounds = mutableMapOf<String, android.graphics.Rect>()
            for (label in listOf("Close player", "Previous track", "Play", "Next track", "Queue", "Volume")) {
                val deadline = SystemClock.uptimeMillis() + 5_000
                var found = control(instrumentation.uiAutomation.rootInActiveWindow, label, false)
                while (found == null && SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(100)
                    found = control(instrumentation.uiAutomation.rootInActiveWindow, label, false)
                }
                var node = found ?: error("Missing $label")
                if (label != "Volume") {
                    while (!node.isClickable && node.parent != null) node = node.parent
                }
                val rect = android.graphics.Rect().also(node::getBoundsInScreen)
                assertTrue("$label is offscreen: $rect", rect.left >= 0 && rect.top >= 0 &&
                    rect.right <= screen.width && rect.bottom <= screen.height)
                assertTrue("$label touch bounds too small: $rect", rect.width() >= (48 * density).toInt() &&
                    rect.height() >= (48 * density).toInt())
                bounds[label] = rect
            }
            assertTrue("Utility and transport controls overlap", bounds.getValue("Queue").bottom <= bounds.getValue("Play").top)
            screen.recycle()
            repeat(3) {
                val label = if (playing.value) "Pause" else "Play"
                val button = control(instrumentation.uiAutomation.rootInActiveWindow, label)
                    ?: error("Missing $label button")
                assertTrue(button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                SystemClock.sleep(350)
                capture("music-player-toggle")
                val expected = if (playing.value) "Pause" else "Play"
                val deadline = SystemClock.uptimeMillis() + 5_000
                var updated = control(instrumentation.uiAutomation.rootInActiveWindow, expected)
                while (updated == null && SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(100)
                    updated = control(instrumentation.uiAutomation.rootInActiveWindow, expected)
                }
                assertNotNull("Missing $expected after toggle", updated)
            }
            assertTrue(playing.value)
            capture("music-player-playing")
            for (type in listOf(MediaType.ANIME, MediaType.MANGA)) {
                show { CatalogueScreen(CatalogueUiState(mediaType = type,
                    sourceName = "A provider with a deliberately long name for layout verification"), {}, {}, {}, {}) }
                capture("catalogue-${type.name.lowercase()}")
            }
        }
    }
}
