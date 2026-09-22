package com.coin11.taojinbi.observation

import java.util.concurrent.CopyOnWriteArraySet

object ObserverState {
    private val listeners = CopyOnWriteArraySet<(Observation?) -> Unit>()

    @Volatile
    var latestExternalObservation: Observation? = null
        private set

    @Volatile
    var latestObservationValid: Boolean = false
        private set

    @Volatile
    var invalidationReason: String? = null
        private set

    @Volatile
    var latestEventPackageName: String? = null
        private set

    @Volatile
    var latestEventClassName: String? = null
        private set

    @Volatile
    var latestWindowStateClassName: String? = null
        private set

    fun updateEvent(
        packageName: String?,
        className: String?,
        isWindowStateChange: Boolean,
    ) {
        latestEventPackageName = packageName
        latestEventClassName = className
        if (isWindowStateChange && !className.isNullOrBlank()) {
            latestWindowStateClassName = className
        }
    }

    fun publish(observation: Observation) {
        latestExternalObservation = observation
        latestObservationValid = true
        invalidationReason = null
        notifyListeners()
    }

    fun invalidate(reason: String) {
        if (latestExternalObservation == null) return
        latestObservationValid = false
        invalidationReason = reason
        notifyListeners()
    }

    fun addListener(listener: (Observation?) -> Unit) {
        listeners += listener
        listener(latestExternalObservation)
    }

    fun removeListener(listener: (Observation?) -> Unit) {
        listeners -= listener
    }

    private fun notifyListeners() {
        val observation = latestExternalObservation
        listeners.forEach { listener ->
            runCatching { listener(observation) }
        }
    }
}
