package com.coin11.taojinbi.ocr

import android.graphics.Bitmap
import android.os.SystemClock
import com.coin11.taojinbi.observation.IntRect
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
        callback: (Result<OcrRecognition>) -> Unit,
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        val image = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { result ->
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val lines = result.textBlocks
                    .flatMap { it.lines }
                    .mapNotNull { line ->
                        val value = line.text.trim()
                        if (value.isBlank()) {
                            null
                        } else {
                            val bounds = line.boundingBox?.let {
                                IntRect(
                                    left = it.left,
                                    top = it.top,
                                    right = it.right,
                                    bottom = it.bottom,
                                )
                            }
                            OcrLine(
                                text = value,
                                bounds = bounds,
                            )
                        }
                    }

                callback(
                    Result.success(
                        OcrRecognition(
                            elapsedMillis = elapsed,
                            lines = lines,
                        ),
                    ),
                )
            }
            .addOnFailureListener { error ->
                callback(Result.failure(error))
            }
    }
}
