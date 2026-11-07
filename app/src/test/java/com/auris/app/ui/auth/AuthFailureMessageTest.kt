package com.auris.app.ui.auth

import io.github.jan.supabase.exceptions.RestException
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthFailureMessageTest {
    @Test fun `linkage failures never show obfuscated class names`() {
        assertEquals(
            "Authentication could not start. Please install the latest app update.",
            authFailureMessage(NoClassDefFoundError("h5.i"), registering = true),
        )
    }

    @Test fun `server rejection uses a safe actionable message`() {
        assertEquals(
            "Sign-in failed. Check your email and password.",
            authFailureMessage(RestException("secret request", "details", 400, "url"), false),
        )
    }

    @Test fun `unknown errors do not expose request details`() {
        assertEquals(
            "Registration failed. Please try again later.",
            authFailureMessage(IllegalStateException("private request contents"), true),
        )
    }
}
