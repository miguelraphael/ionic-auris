package com.auris.app.ui.auth

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auris.app.MainActivity
import com.auris.app.ui.theme.AurisTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AuthLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        fun visit(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            n ?: return null
            if (predicate(n)) return n
            for (i in 0 until n.childCount) visit(n.getChild(i))?.let { return it }
            return null
        }
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            visit(instrumentation.uiAutomation.rootInActiveWindow)?.let { return it }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        fun describe(n: AccessibilityNodeInfo?): String {
            n ?: return ""
            val text = if (n.isEditable || n.isPassword) "[field]" else n.text
            return "${n.className}:$text:${n.contentDescription}:click=${n.isClickable}\n" +
                (0 until n.childCount).joinToString("") { describe(n.getChild(it)) }
        }
        error("Expected auth control did not appear\n" + describe(instrumentation.uiAutomation.rootInActiveWindow))
    }

    private fun action(label: String): AccessibilityNodeInfo {
        var node = find { candidate ->
            candidate.text?.toString() == label &&
                generateSequence(candidate) { it.parent }.any { it.isClickable }
        }
        while (!node.isClickable && node.parent != null) node = node.parent
        return node
    }

    private fun capture(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.cacheDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun keyboardModeSwitchAndErrorStatesRemainUsable() {
        val state = mutableStateOf(AuthUiState())
        var submissions = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                activity.setContent {
                    AurisTheme {
                        AuthScreen(state.value,
                            onEmailChange = { state.value = state.value.copy(email = it) },
                            onPasswordChange = { state.value = state.value.copy(password = it) },
                            onToggleMode = { state.value = state.value.copy(isRegisterMode = !state.value.isRegisterMode) },
                            onSubmit = { submissions++ })
                    }
                }
            }
            instrumentation.waitForIdleSync()
            find { it.isEditable }
            SystemClock.sleep(800)
            capture("auth-default")
            val email = find { it.isEditable && !it.isPassword }
            assertTrue(email.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "viewer@example.com")
            }))
            val password = find { it.isEditable && it.isPassword }
            assertTrue(password.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "sample-password")
            }))
            password.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            SystemClock.sleep(800)
            find { it.isScrollable }.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(600)
            capture("auth-after-scroll")
            var submit = action("Sign in")
            // Material may expose the label on a child instead of the clickable parent.
            submit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(500)
            submit = action("Sign in")
            val bounds = Rect().also(submit::getBoundsInScreen)
            var ime = 0
            var height = 0
            scenario.onActivity {
                ime = ViewCompat.getRootWindowInsets(it.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0

            }
            val screen = instrumentation.uiAutomation.takeScreenshot()
            height = screen.height
            screen.recycle()
            assertTrue("Keyboard must be open", ime > 0)
            assertTrue("Submit hidden behind keyboard: $bounds", bounds.bottom <= height - ime)
            capture("auth-keyboard")
            assertTrue(submit.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            assertEquals(1, submissions)
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            SystemClock.sleep(400)
            find { it.isScrollable }.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            SystemClock.sleep(600)
            var mode = action("Create account")
            mode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            SystemClock.sleep(400)
            mode = action("Create account")
            assertTrue(mode.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            assertTrue(state.value.isRegisterMode)
            scenario.onActivity { state.value = state.value.copy(emailError = "Enter a valid email address", passwordError = "Use at least 8 characters") }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(400)
            capture("auth-field-errors")
            assertEquals("viewer@example.com", state.value.email)
            assertEquals("sample-password", state.value.password)
        }
    }
}
