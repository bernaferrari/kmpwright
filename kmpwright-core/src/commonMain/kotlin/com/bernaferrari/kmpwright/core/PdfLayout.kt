package com.bernaferrari.kmpwright.core

/** Physical print dimensions, expressed in inches at the protocol boundary. */
internal fun PdfOptions.paperSizeInches(): Pair<Double, Double> {
    val portrait = when (format) {
        PdfFormat.Letter -> 8.5 to 11.0
        PdfFormat.Legal -> 8.5 to 14.0
        PdfFormat.Tabloid -> 11.0 to 17.0
        PdfFormat.Ledger -> 17.0 to 11.0
        PdfFormat.A0 -> 841.0 / 25.4 to 1189.0 / 25.4
        PdfFormat.A1 -> 594.0 / 25.4 to 841.0 / 25.4
        PdfFormat.A2 -> 420.0 / 25.4 to 594.0 / 25.4
        PdfFormat.A3 -> 297.0 / 25.4 to 420.0 / 25.4
        PdfFormat.A4 -> 210.0 / 25.4 to 297.0 / 25.4
        PdfFormat.A5 -> 148.0 / 25.4 to 210.0 / 25.4
        PdfFormat.A6 -> 105.0 / 25.4 to 148.0 / 25.4
        PdfFormat.Content -> throw UnsupportedOperationException("Content canvas PDFs require WKWebView")
    }
    val size = (width ?: portrait.first) to (height ?: portrait.second)
    require(size.first.isFinite() && size.second.isFinite() && size.first > 0 && size.second > 0) { "PDF dimensions must be positive inches" }
    return if (landscape) size.second to size.first else size
}

internal fun pdfLengthInches(value: String): Double {
    val match = Regex("""^\s*(\d+(?:\.\d+)?)\s*(in|cm|mm|px|pt)?\s*$""").matchEntire(value)
        ?: throw IllegalArgumentException("Invalid PDF margin: $value")
    val number = match.groupValues[1].toDouble()
    return when (match.groupValues[2]) {
        "in" -> number
        "cm" -> number / 2.54
        "mm" -> number / 25.4
        "pt" -> number / 72
        else -> number / 96
    }
}
