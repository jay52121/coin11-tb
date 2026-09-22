package com.coin11.taojinbi.ocr

import java.util.concurrent.CopyOnWriteArraySet

object OcrState {
    private val listeners = CopyOnWriteArraySet<(OcrSnapshot?) -> Unit>()

    @Volatile
    var latest: OcrSnapshot? = null
        private set

    fun publish(snapshot: OcrSnapshot) {
        latest = snapshot
        listeners.forEach { listener ->
            runCatching { listener(snapshot) }
        }
    }

    fun addListener(listener: (OcrSnapshot?) -> Unit) {
        listeners += listener
        listener(latest)
    }

    fun removeListener(listener: (OcrSnapshot?) -> Unit) {
        listeners -= listener
    }
}
