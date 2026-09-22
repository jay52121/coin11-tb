package com.coin11.taojinbi.ocr

import com.coin11.taojinbi.observation.IntRect

data class OcrLine(
    val text: String,
    val bounds: IntRect?,
)

data class OcrRecognition(
    val elapsedMillis: Long,
    val lines: List<OcrLine>,
)

data class OcrSnapshot(
    val observationId: Long?,
    val capturedAtMillis: Long,
    val screenshotWidth: Int,
    val screenshotHeight: Int,
    val screenshotElapsedMillis: Long,
    val recognitionElapsedMillis: Long,
    val lines: List<OcrLine>,
) {
    fun debugText(maxLines: Int = 24): String = buildString {
        appendLine("Observation：" + (observationId?.let { "#" + it } ?: "(none)"))
        appendLine("Screenshot：" + screenshotWidth + "x" + screenshotHeight + " / " + screenshotElapsedMillis + "ms")
        appendLine("OCR：" + recognitionElapsedMillis + "ms / " + lines.size + " lines")
        lines.take(maxLines).forEach { line ->
            val boundsText = line.bounds?.toString()?.plus("  ").orEmpty()
            appendLine(boundsText + line.text)
        }
        if (lines.size > maxLines) {
            append("...还有 " + (lines.size - maxLines) + " 行")
        }
    }.trimEnd()
}
