package com.auris.app.music

import android.os.Bundle
import android.os.Process
import androidx.media3.session.MediaSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MusicControllerTrustTest {
    private fun controller(uid: Int, trusted: Boolean, name: String = "other.app") =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(name, 42, uid, 1, 1, trusted, Bundle.EMPTY)

    @Test fun rejectsUntrustedOtherUidEvenWithAurisPackageName() {
        val service = MusicPlaybackService()
        assertFalse(service.isAllowedController(controller(Process.myUid() + 1, false, "com.auris.app")))
    }

    @Test fun permitsOwnUidWithoutSystemTrust() {
        assertTrue(MusicPlaybackService().isAllowedController(controller(Process.myUid(), false)))
    }

    @Test fun permitsSystemTrustedMediaController() {
        assertTrue(MusicPlaybackService().isAllowedController(controller(Process.myUid() + 1, true)))
    }
}
