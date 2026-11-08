package com.auris.app.ui.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.auris.app.TestApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApplication::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerRecoveryTest {
    @Test fun failedConnectionCanBeRetriedWithoutGettingStuck() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as android.app.Application).declareComponentUnbindable(
            android.content.ComponentName(context, com.auris.app.music.MusicPlaybackService::class.java),
        )
        val viewModel = PlayerViewModel(context)
        val store = androidx.lifecycle.ViewModelStore().apply { put("player", viewModel) }
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(viewModel.state.value.error)
            assertFalse(viewModel.state.value.isReconnecting)
            viewModel.retryPlayback()
            viewModel.retryPlayback()
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(viewModel.state.value.error)
            assertFalse(viewModel.state.value.isReconnecting)
        } finally {
            store.clear()
        }
    }

    @Test fun retryPreparesAndPlaysWithoutReplacingQueueOrResettingPosition() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = ExoPlayer.Builder(context).build()
        var prepares = 0
        var plays = 0
        val player = object : ForwardingPlayer(engine) {
            override fun prepare() { prepares++ }
            override fun play() { plays++ }
        }
        engine.setMediaItems(listOf("first", "second").map { id ->
            MediaItem.Builder().setMediaId(id).setUri("https://example.com/$id")
                .setMediaMetadata(MediaMetadata.Builder().setTitle(id).build()).build()
        }, 1, 12_000L)
        val session = MediaSession.Builder(context, player).build()
        val future = MediaController.Builder(context, session.token).buildAsync()
        shadowOf(Looper.getMainLooper()).idle()
        val controller = future.get(5, java.util.concurrent.TimeUnit.SECONDS)
        shadowOf(context as android.app.Application).declareComponentUnbindable(
            android.content.ComponentName(context, com.auris.app.music.MusicPlaybackService::class.java),
        )
        val viewModel = PlayerViewModel(context)
        val store = androidx.lifecycle.ViewModelStore().apply { put("player", viewModel) }
        shadowOf(Looper.getMainLooper()).idle()
        val field = PlayerViewModel::class.java.getDeclaredField("controller").apply { isAccessible = true }
        field.set(viewModel, controller)
        // Inject the real local session after the service connection fails.
        val stateField = PlayerViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<PlayerUiState>
        state.value = PlayerUiState(error = "Playback failed", isReconnecting = true)
        try {
            viewModel.retryPlayback()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, prepares)
            assertEquals(0, plays)
            state.value = state.value.copy(isReconnecting = false)
            viewModel.retryPlayback()
            shadowOf(Looper.getMainLooper()).idle()
            // MediaSession may also prepare an idle player when handling play.
            assertTrue(prepares > 0)
            assertEquals(1, plays)
            assertEquals(2, engine.mediaItemCount)
            assertEquals("second", engine.currentMediaItem?.mediaId)
            assertEquals(12_000L, engine.currentPosition)
            assertEquals("second", viewModel.state.value.title)
            assertNull(viewModel.state.value.error)
        } finally {
            field.set(viewModel, null)
            store.clear()
            MediaController.releaseFuture(future)
            session.release()
            engine.release()
        }
    }
}
