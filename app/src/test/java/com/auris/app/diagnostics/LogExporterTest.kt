package com.auris.app.diagnostics

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auris.app.TestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The share-grant shape for diagnostic bundles.
 *
 * A missing read grant fails on the *receiving* side as `SecurityException`,
 * which the exporter never sees — verified on Oplus/ColorOS, where the flag
 * alone (no `ClipData`) was denied. So the grant is asserted directly here
 * rather than by sharing on device.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = TestApplication::class)
class LogExporterTest {

    private val exporter = LogExporter(
        context = ApplicationProvider.getApplicationContext(),
        buffer = AurisLogBuffer(),
        appStateProvider = { "test" },
        includeLogcat = false,
    )

    @Test
    fun shareIntentCarriesStreamAndSubject() {
        val uri = Uri.parse("content://com.auris.app.fileprovider/diagnostics/x.txt")

        val intent = exporter.shareIntent(uri)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM))
    }

    @Test
    fun shareIntentGrantsUriReadThroughClipData() {
        val uri = Uri.parse("content://com.auris.app.fileprovider/diagnostics/x.txt")

        val intent = exporter.shareIntent(uri)

        val clip = intent.clipData
        assertNotNull(clip)
        assertEquals(uri, clip!!.getItemAt(0).uri)
        assertTrue(
            "FLAG_GRANT_READ_URI_PERMISSION must be set",
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
    }
}
