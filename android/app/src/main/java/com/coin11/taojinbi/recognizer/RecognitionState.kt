package com.coin11.taojinbi.recognizer

import java.util.concurrent.CopyOnWriteArraySet

data class RecognitionSnapshot(
    val observationId: Long,
    val recognizedAtMillis: Long,
    val activityHint: String?,
    val result: RecognitionResult,
)

object RecognitionState {
    private val listeners = CopyOnWriteArraySet<(RecognitionSnapshot?) -> Unit>()

    @Volatile
    var latest: RecognitionSnapshot? = null
        private set

    @Volatile
    var rulesSource: String = "not loaded"
        private set

    @Volatile
    var rulesError: String? = null
        private set

    fun configureRules(source: String, error: String?) {
        rulesSource = source
        rulesError = error
    }

    fun publish(snapshot: RecognitionSnapshot) {
        latest = snapshot
        listeners.forEach { listener ->
            runCatching { listener(snapshot) }
        }
    }

    fun addListener(listener: (RecognitionSnapshot?) -> Unit) {
        listeners += listener
        listener(latest)
    }

    fun removeListener(listener: (RecognitionSnapshot?) -> Unit) {
        listeners -= listener
    }
}
