package com.bernaferrari.kmpwright.core

/**
 * Delegate for scrolling operations.
 * Extracted from BasePage to reduce class size and improve testability.
 */
internal class PageScrollDelegate(
    private val evalString: suspend (String) -> String?
) {
    suspend fun scroll(x: Double, y: Double) {
        evalString("window.scrollBy($x, $y);")
    }

    suspend fun scrollDown(distance: Double) {
        val d = if (distance > 0) distance else 0.0
        evalString("window.scrollBy(0, $d + window.innerHeight * 0.8);")
    }

    suspend fun scrollUp(distance: Double) {
        val d = if (distance > 0) distance else 0.0
        evalString("window.scrollBy(0, -($d + window.innerHeight * 0.8));")
    }

    suspend fun scrollToLeft(distance: Double) {
        val d = if (distance > 0) distance else 0.0
        evalString("window.scrollBy(-($d + window.innerWidth * 0.8), 0);")
    }

    suspend fun scrollToTop() {
        evalString("window.scrollTo(0, 0);")
    }

    suspend fun scrollToBottom() {
        evalString("window.scrollTo(0, document.body.scrollHeight || document.documentElement.scrollHeight);")
    }
}
