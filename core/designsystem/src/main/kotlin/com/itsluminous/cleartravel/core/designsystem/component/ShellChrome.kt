package com.itsluminous.cleartravel.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * ADR-034: lets a screen deep inside a tab (the fullscreen document viewer) ask the
 * app shell to hide ITS chrome too — the bottom `NavigationBar`, the Journeys
 * segmented control. Reference-counted so overlapping requests never un-hide early,
 * and always released through [HideShellChrome]'s `DisposableEffect`, so a viewer
 * that is popped while fullscreen leaves the shell intact. The shell provides one
 * instance through [LocalShellChrome]; the default is inert (nothing to hide).
 */
class ShellChromeController {
    private var hideRequests by mutableIntStateOf(0)

    /** True while at least one screen asked for a chrome-free shell. */
    val hidden: Boolean get() = hideRequests > 0

    internal fun acquire() {
        hideRequests++
    }

    internal fun release() {
        if (hideRequests > 0) hideRequests--
    }
}

val LocalShellChrome = compositionLocalOf { ShellChromeController() }

/** Holds a hide request on the shell chrome while [hide] is true and this is composed. */
@Composable
fun HideShellChrome(hide: Boolean) {
    val controller = LocalShellChrome.current
    DisposableEffect(controller, hide) {
        if (hide) controller.acquire()
        onDispose { if (hide) controller.release() }
    }
}
