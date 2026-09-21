package com.itsluminous.cleartravel.core.scrape

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView

/**
 * Shared touch/scroll configuration for EVERY WebView the app hosts inside Compose
 * (`AndroidView`): the PNR check, the route fetch, the flight status check and its
 * web-search fallback. Applied in one place so the hosts can't drift apart.
 *
 * - Scrollbars + over-scroll only when the page is taller than the viewport, so the
 *   user can see the page scrolls at all.
 * - The touch stream is claimed on ACTION_DOWN via `requestDisallowInterceptTouchEvent`:
 *   any ancestor `ViewGroup` that intercepts vertical drags (a scrolling container,
 *   a swipe-to-dismiss host, interop wrappers) would leave taps working while swipes
 *   silently die — exactly the "taps work, swipes don't, PAGE_DOWN works" symptom.
 *   The listener never consumes the event (returns false), so clicks/accessibility
 *   still reach the WebView unchanged.
 */
@SuppressLint("ClickableViewAccessibility") // returns false: WebView's own click/a11y handling is untouched
fun WebView.configureTouchScrolling() {
    isVerticalScrollBarEnabled = true
    isHorizontalScrollBarEnabled = false
    overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
    setOnTouchListener { view, event ->
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            view.parent?.requestDisallowInterceptTouchEvent(true)
        }
        false
    }
}
