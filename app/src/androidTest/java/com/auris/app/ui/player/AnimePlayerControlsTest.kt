package com.auris.app.ui.player

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auris.app.MainActivity
import com.auris.app.core.model.MediaType
import com.auris.app.data.local.LibraryDao
import com.auris.app.data.local.SyncDao
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.data.local.entity.LibraryUnitEntity
import com.auris.app.data.repository.ExtensionContentRepository
import com.auris.app.data.repository.LibraryRepository
import com.auris.app.data.sync.SyncScheduler
import com.auris.app.source.*
import com.auris.app.ui.theme.AurisTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType

/** Local media and isolated repository doubles: no account, network, or user-library writes. */
@RunWith(AndroidJUnit4::class)
class AnimePlayerControlsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun pausedSeeksRotationAndWindowCleanup() {
        val context = instrumentation.targetContext
        val samples = 8_000 * 90
        val audio = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(samples * 2)
            repeat(samples) { putShort(0) }
        }.array()
        val entry = LibraryEntryEntity(id = "player-test", mediaType = MediaType.ANIME,
            sourceId = "test-player", sourceKey = "test", title = "Player regression test")
        val unit = LibraryUnitEntity(entry.id, 1, "episode", "Episode 1")
        val dao = proxy<LibraryDao> { name -> when (name) {
            "observeUnits" -> MutableStateFlow(listOf(unit))
            "updateProgress", "updateUnitProgress" -> Unit
            else -> error("Unexpected library call: $name")
        } }
        val sync = proxy<SyncDao> { 1L }
        val library = LibraryRepository(dao, sync, SyncScheduler(context), Dispatchers.IO, Json)
        val source = proxy<MediaSource> { name -> when (name) {
            "getId" -> entry.sourceId
            "getMediaType" -> MediaType.ANIME
            "streams" -> listOf(StreamTarget("https://player.test/episode.wav", mimeType = "audio/wav", label = "Local test"))
            else -> error("Unexpected source call: $name")
        } }
        val registry = object : ExtensionSourceRegistry { override val sources = emptyList<LoadedSource>() }
        val content = ExtensionContentRepository(registry, { _, _ -> }, builtInSources = listOf(source))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val start = chain.request().header("Range")?.removePrefix("bytes=")
                ?.substringBefore('-')?.toIntOrNull() ?: 0
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (start > 0) 206 else 200).message("OK")
                .header("Content-Length", (audio.size - start).toString())
                .header("Content-Range", "bytes $start-${audio.lastIndex}/${audio.size}")
                .body(audio.copyOfRange(start, audio.size).toResponseBody("audio/wav".toMediaType()))
                .build()
        }.build()
        val playback = EpisodePlayer(context, client)
        val visible = mutableStateOf(true)
        var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    originalOrientation = activity.requestedOrientation
                    activity.setContent {
                        AurisTheme {
                            if (visible.value) EpisodePlayerRoute(entry, 1, library, content, playback,
                                onBack = { visible.value = false })
                        }
                    }
                }
                await("Player did not prepare") { main { playback.player.value?.playbackState == androidx.media3.common.Player.STATE_READY } }
                main { playback.player.value!!.pause(); playback.player.value!!.seekTo(30_000) }
                await("Paused position was not displayed") { node("0:30") != null }
                click("Forward 10 seconds")
                await("Paused forward seek did not update timestamp") { node("0:40") != null }
                assertTrue(main { playback.player.value!!.currentPosition in 39_900L..40_100L })
                assertFalse(main { playback.player.value!!.playWhenReady })
                click("Back 10 seconds")
                await("Paused rewind did not update timestamp") { node("0:30") != null }
                assertFalse(main { playback.player.value!!.playWhenReady })
                val arguments = android.os.Bundle().apply {
                    putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 0.5f)
                }
                assertTrue(node("Playback position")!!.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, arguments))
                await("Paused scrub did not update timestamp") { node("0:45") != null }
                assertFalse(main { playback.player.value!!.playWhenReady })
                await("Player did not open in landscape") {
                    var landscape = false
                    scenario.onActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                    landscape
                }
                scenario.onActivity {
                    assertTrue("Player must keep the screen awake", keepsAwake(it.window.decorView))
                    assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, it.requestedOrientation)
                }
                screenshot("player-landscape")
                click("Exit fullscreen")
                await("Fullscreen button did not switch to portrait") { node("Fullscreen") != null }
                scenario.onActivity { assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, it.requestedOrientation) }
                screenshot("player-portrait")
                click("Fullscreen")
                await("Fullscreen button did not return to landscape") { node("Exit fullscreen") != null }
                click("Back")
                await("Player was not released on exit") { main { playback.player.value == null } }
                scenario.onActivity {
                    assertEquals(originalOrientation, it.requestedOrientation)
                    assertFalse("Screen wake flag leaked after exit", keepsAwake(it.window.decorView))
                }
            }
        } finally {
            main { playback.release() }
        }
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        // The configuration arrives before Android's rotation animation finishes.
        SystemClock.sleep(800)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.cacheDir, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun keepsAwake(view: View): Boolean = view.keepScreenOn ||
        (view is ViewGroup && (0 until view.childCount).any { keepsAwake(view.getChildAt(it)) })

    private inline fun <reified T> proxy(crossinline answer: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> answer(method.name) } as T

    private fun <T> main(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }

    private fun node(label: String): AccessibilityNodeInfo? {
        fun find(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (root.text?.toString() == label || root.contentDescription?.toString() == label) return root
            for (index in 0 until root.childCount) root.getChild(index)?.let { find(it)?.let { match -> return match } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
    }

    private fun click(label: String) {
        await("Missing button: $label") { node(label) != null }
        var target = node(label)!!
        while (!target.isClickable && target.parent != null) target = target.parent
        assertTrue("Button did not accept click: $label", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        fail(message)
    }
}
