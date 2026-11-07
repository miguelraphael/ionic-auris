package com.auris.app.source

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auris.app.data.remote.anime.AllAnimeSource
import com.auris.app.ui.player.EpisodePlayer
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicitly opt-in live test: the production connector, HTTP headers, HLS module and decoder. */
@RunWith(AndroidJUnit4::class)
class LiveAnimePlaybackTest {
    @Test fun rendersFirstFrameOfResolvedEpisode() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("aurisLiveAnime") == "true")
        val source = AllAnimeSource()
        val title = source.search(1, "Naruto", FilterList()).items.first()
        val episode = source.units(title).first()
        val targets = source.streams(title, episode)
        assertTrue(targets.isNotEmpty())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val completed = CountDownLatch(1)
        var playback: EpisodePlayer? = null
        var texture: SurfaceTexture? = null
        var surface: Surface? = null
        var rendered = false
        var failure: String? = null
        try {
            instrumentation.runOnMainSync {
                texture = SurfaceTexture(0)
                surface = Surface(texture)
                playback = EpisodePlayer(instrumentation.targetContext)
                val player = playback!!.play(targets)!!
                player.setVideoSurface(surface)
                player.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { rendered = true; completed.countDown() }
                    override fun onPlayerError(error: PlaybackException) {
                        failure = "${error.errorCodeName}: ${error.cause?.javaClass?.simpleName}"
                        completed.countDown()
                    }
                })
            }
            assertTrue("Timed out waiting for a decoded video frame", completed.await(45, TimeUnit.SECONDS))
            assertNull("Playback failed: $failure", failure)
            assertTrue("No video frame rendered", rendered)
            instrumentation.runOnMainSync {
                val size = playback!!.player.value!!.videoSize
                assertTrue("Video has no dimensions", size.width > 0 && size.height > 0)
                android.util.Log.i("AurisLiveAnime", "FIRST_FRAME ${size.width}x${size.height}; ${targets.first().subtitles.size} subtitle tracks")
            }
        } finally {
            instrumentation.runOnMainSync {
                playback?.release()
                surface?.release()
                texture?.release()
            }
        }
    }
}
