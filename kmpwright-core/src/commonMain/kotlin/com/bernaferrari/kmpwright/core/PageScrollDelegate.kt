package com.bernaferrari.kmpwright.core

/** Window scrolling for [Page.scroll], [Page.scrollToTop] and [Page.scrollToBottom]. */
internal class PageScrollDelegate(
    private val evalString: suspend (String) -> String?
) {
    suspend fun scroll(direction: ScrollDirection, amount: Double) {
        require(amount.isFinite() && amount >= 0) { "Scroll amount must be a non-negative number" }
        val (x, y) = when (direction) {
            ScrollDirection.Up -> 0.0 to -amount
            ScrollDirection.Down -> 0.0 to amount
            ScrollDirection.Left -> -amount to 0.0
            ScrollDirection.Right -> amount to 0.0
        }
        evalString("window.scrollBy($x, $y);")
    }

    suspend fun scrollToTop() {
        evalString("window.scrollTo(0, 0);")
    }

    suspend fun scrollToBottom() {
        evalString("window.scrollTo(0, document.body.scrollHeight || document.documentElement.scrollHeight);")
    }
}
