package com.rumor.mesh.ui

import android.app.Activity
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import java.util.concurrent.atomic.AtomicInteger

/**
 * O165: marks the hosting window `FLAG_SECURE` for as long as the calling
 * screen is composed — blocking screenshots, screen recording, and the
 * snapshot the OS takes for the recent-apps card.
 *
 * Call it from any screen that puts a secret on glass: passphrase entry
 * (onboarding, change-passphrase, blocklist export/import) and decrypted DM
 * bodies. O45 already mandates this for the future mnemonic-export screen; the
 * equally sensitive screens that exist today had none.
 *
 * The flag is window-global but the need is per-screen, so a naive
 * add-on-enter/clear-on-exit pair breaks when two secure screens overlap:
 * navigating away disposes the *old* screen after the new one is composed, and
 * its clear would strip protection from the screen still showing. Hence the
 * refcount — the flag drops only when the last secure screen leaves.
 */
private val secureScreens = AtomicInteger(0)

/**
 * User setting, ON by default: some users want to screenshot their own DMs.
 * Read when a secure screen is entered, so a change applies from the next one.
 * Residual risk: anyone holding the unlocked phone can turn it off unnoticed.
 */
object ScreenshotPolicy {
    private const val PREFS = "rumor_ui"
    private const val KEY = "block_screenshots"

    fun isBlocking(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun setBlocking(context: android.content.Context, blocking: Boolean) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, blocking).apply()
    }
}

@Composable
fun SecureScreen() {
    val view = LocalView.current
    val blocking = androidx.compose.runtime.remember { ScreenshotPolicy.isBlocking(view.context) }
    if (!blocking) return
    DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
        if (window != null && secureScreens.getAndIncrement() == 0) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (window != null && secureScreens.decrementAndGet() == 0) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}

private fun android.content.Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
