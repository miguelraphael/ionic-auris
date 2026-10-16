package com.auris.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for instance-URL normalisation.
 *
 * ### Why this is worth pinning
 * The list is typed by hand, so every accepted form is a form someone will paste, and every
 * rejected one is a typo that would otherwise be stored and only surface later as a transport
 * error at playback time — blamed on the instance rather than on the entry.
 */
class InvidiousInstanceStoreTest {

    @Test
    fun `accepts the forms a user actually pastes`() {
        assertEquals("https://invidious.f5.si", InvidiousInstanceStore.normalise("https://invidious.f5.si"))
        assertEquals("https://invidious.f5.si", InvidiousInstanceStore.normalise("invidious.f5.si"))
        assertEquals("https://invidious.f5.si", InvidiousInstanceStore.normalise("  https://invidious.f5.si/  "))
        // A trailing slash would produce a double slash in `/api/v1/...`, which some instances
        // answer with a 404 rather than redirecting.
        assertEquals("https://host.example", InvidiousInstanceStore.normalise("https://host.example/"))
        // Plain http is left alone rather than upgraded: a self-hosted instance on a LAN is the
        // user's call, not ours.
        assertEquals("http://192.168.1.10", InvidiousInstanceStore.normalise("http://192.168.1.10"))
    }

    @Test
    fun `rejects what cannot be a public instance`() {
        // Blank and whitespace.
        assertNull(InvidiousInstanceStore.normalise(""))
        assertNull(InvidiousInstanceStore.normalise("   "))
        // A bare word is not a host, and storing it would fail only at playback.
        assertNull(InvidiousInstanceStore.normalise("localhost"))
        assertNull(InvidiousInstanceStore.normalise("not a host"))
        // Some other scheme; not ours to guess at.
        assertNull(InvidiousInstanceStore.normalise("ftp://host.example"))
    }

    @Test
    fun `a host with a path is kept, because instances can live under a prefix`() {
        assertEquals(
            "https://host.example/invidious",
            InvidiousInstanceStore.normalise("host.example/invidious/"),
        )
    }
}
