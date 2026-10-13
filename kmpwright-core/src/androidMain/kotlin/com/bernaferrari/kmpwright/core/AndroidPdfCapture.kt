package com.bernaferrari.kmpwright.core

import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.min

internal fun validateAndroidPdfOptions(options: PdfOptions) {
    options.paperSizeInches()
    listOf(options.margin.top, options.margin.bottom, options.margin.left, options.margin.right).forEach { pdfLengthInches(it) }
    if (options.displayHeaderFooter || options.headerTemplate.isNotEmpty() || options.footerTemplate.isNotEmpty() ||
        options.scale != 1.0 || !options.printBackground || options.pageRanges.isNotEmpty()) {
        throw KmpwrightUnsupportedException("Android PDF capture supports paper size, orientation and margins; print templates, scale, background suppression and page ranges are unavailable")
    }
}

/** Paginate rendered viewport tiles. Allocation is bounded; no whole-document bitmap is created. */
internal suspend fun captureAndroidPdf(webView: WebView, options: PdfOptions): ByteArray = withContext(Dispatchers.Main) {
    validateAndroidPdfOptions(options)
    val size = options.paperSizeInches()
    val paperWidth = (size.first * 72).toInt()
    val paperHeight = (size.second * 72).toInt()
    val left = pdfLengthInches(options.margin.left) * 72
    val top = pdfLengthInches(options.margin.top) * 72
    val printableWidth = paperWidth - left - pdfLengthInches(options.margin.right) * 72
    val printableHeight = paperHeight - top - pdfLengthInches(options.margin.bottom) * 72
    require(printableWidth > 0 && printableHeight > 0) { "PDF margins leave no printable area" }
    val capture = AndroidScreenshotController(webView)
    val dimensions = capture.measurePageDimensions()
    if (dimensions.contentWidth.toLong() * dimensions.contentHeight > 16L * 1024 * 1024) {
        throw KmpwrightException("Android PDF capture exceeds the 16-megapixel document limit")
    }
    val originalScroll = capture.readScrollPosition()
    val vertical = webView.isVerticalScrollBarEnabled
    val horizontal = webView.isHorizontalScrollBarEnabled
    val document = PdfDocument()
    try {
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        // Native contentHeight rounds CSS units and can overstate the final
        // drawable pixels. Measure the renderer's clamped scroll extent.
        capture.scrollToEndForCapture()
        // The last viewport must start at a whole drawable pixel. Rounding
        // a fractional maximum outward creates a phantom one-pixel tile.
        val end = capture.scrollPixelsForCapture(roundDown = true)
        val bounded = dimensions.copy(
            contentWidth = min(dimensions.contentWidth, end.first + dimensions.viewportWidth),
            contentHeight = min(dimensions.contentHeight, end.second + dimensions.viewportHeight))
        val ratio = printableWidth / bounded.contentWidth
        val sourcePageHeight = (printableHeight / ratio).toInt().coerceAtLeast(1)
        val pageCount = ceil(bounded.contentHeight.toDouble() / sourcePageHeight).toInt()
        if (pageCount > 128) throw KmpwrightException("Android PDF capture exceeds 128 pages")
        repeat(pageCount) { index ->
            val pageStart = index * sourcePageHeight
            val pageEnd = min(bounded.contentHeight, pageStart + sourcePageHeight)
            val page = document.startPage(PdfDocument.PageInfo.Builder(paperWidth, paperHeight, index + 1).create())
            try {
                var documentY = pageStart
                while (documentY < pageEnd) {
                    var documentX = 0
                    var rowHeight = 0
                    while (documentX < bounded.contentWidth) {
                        val x = documentX.coerceAtMost((bounded.contentWidth - dimensions.viewportWidth).coerceAtLeast(0))
                        val y = documentY.coerceAtMost((bounded.contentHeight - dimensions.viewportHeight).coerceAtLeast(0))
                        capture.scrollForCapture(x, y)
                        val image = capture.captureViewport()
                        val scroll = capture.scrollPixelsForCapture()
                        val bitmap = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)
                            ?: throw KmpwrightException("PDF tile decode failed")
                        try {
                            val sourceX = documentX - scroll.first
                            val sourceY = documentY - scroll.second
                            val width = min(bitmap.width - sourceX, bounded.contentWidth - documentX)
                            val height = min(bitmap.height - sourceY, pageEnd - documentY)
                            if (sourceX < 0 || sourceY < 0 || width <= 0 || height <= 0) throw KmpwrightException("PDF tile is outside the rendered viewport: document=($documentX,$documentY), scroll=(${webView.scrollX},${webView.scrollY}), bitmap=${bitmap.width}x${bitmap.height}, dimensions=$dimensions")
                            val target = RectF((left + documentX * ratio).toFloat(), (top + (documentY - pageStart) * ratio).toFloat(),
                                (left + (documentX + width) * ratio).toFloat(), (top + (documentY - pageStart + height) * ratio).toFloat())
                            page.canvas.drawBitmap(bitmap, Rect(sourceX, sourceY, sourceX + width, sourceY + height), target, null)
                            documentX += width
                            rowHeight = height
                        } finally { bitmap.recycle() }
                    }
                    documentY += rowHeight
                }
            } finally { document.finishPage(page) }
        }
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (length > 32 * 1024 * 1024 - size()) throw KmpwrightException("PDF output exceeds 32 MiB")
                super.write(bytes, offset, length)
            }
            override fun write(value: Int) {
                if (size() == 32 * 1024 * 1024) throw KmpwrightException("PDF output exceeds 32 MiB")
                super.write(value)
            }
        }
        document.writeTo(output)
        output.toByteArray()
    } finally {
        document.close()
        withContext(NonCancellable + Dispatchers.Main) {
            webView.isVerticalScrollBarEnabled = vertical
            webView.isHorizontalScrollBarEnabled = horizontal
            capture.restoreScrollPosition(originalScroll)
        }
    }
}
