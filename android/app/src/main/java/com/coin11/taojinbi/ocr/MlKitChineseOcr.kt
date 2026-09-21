package com.coin11.taojinbi.ocr

import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

object MlKitChineseOcr {

    private val recognizer by lazy {
        TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build(),
        )
    }

    fun recognize(
        bitmap: Bitmap,
        callback: (Result<String>) -> Unit,
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        val image = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { result ->
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val lines = result.textBlocks
                    .flatMap { it.lines }
                    .mapNotNull { line ->
                        val text = line.text.trim()
                        if (text.isBlank()) {
                            null
                        } else {
                            val bounds = line.boundingBox
                            if (bounds == null) {
                                text
                            } else {
                                "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}  $text"
                            }
                        }
                    }

                callback(
                    Result.success(
                        buildString {
                            appendLine("耗时：${elapsed}ms")
                            appendLine("文本行：${lines.size}")
                            appendLine()
                            lines.take(160).forEach(::appendLine)
                            if (lines.size > 160) {
                                append("…还有 ${lines.size - 160} 行未显示")
                            }
                        }.trimEnd(),
                    ),
                )
            }
            .addOnFailureListener { error ->
                callback(Result.failure(error))
            }
    }
}
