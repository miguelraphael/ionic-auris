package com.auris.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auris.app.TestApplication
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = TestApplication::class)
class InvidiousInstancePreferencesTest {
    @Test
    fun `loading old preferences removes credentials and cleartext without restoring defaults`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("auris_invidious_instances", Context.MODE_PRIVATE)
        prefs.edit().putString("instances", "https://user:secret@host.example\nhttp://host.example").commit()

        assertEquals(emptyList<String>(), InvidiousInstanceStore(context).current())
        assertEquals("", prefs.getString("instances", null))
        assertEquals(emptyList<String>(), InvidiousInstanceStore(context).current())
    }
}
