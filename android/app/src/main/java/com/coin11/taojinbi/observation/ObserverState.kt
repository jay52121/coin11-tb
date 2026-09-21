package com.coin11.taojinbi.observation

import java.util.concurrent.CopyOnWriteArraySet

object ObserverState {
    private val listeners = CopyOnWriteArraySet<(Observation?) -> Unit>()

    @Volatile
    var latestExternalObservation: Observation? = null
        private set

    @Volatile
    var latestEventPackageName: String? = null
        private set

    @Volatile
    var latestEventClassName: String? = null
        private set

    fun updateEvent(packageName: String?, className: String?) {
        latestEventPackageName = packageName
        latestEventClassName = className
    }

    fun publish(observation: Observation) {
        latestExternalObservation = observation
        listeners.forEach { listener ->
            runCatching { listener(observation) }
        }
    }

    fun addListener(listener: (Observation?) -> Unit) {
        listeners += listener
        listener(latestExternalObservation)
    }

    fun removeListener(listener: (Observation?) -> Unit) {
        listeners -= listener
    }
}
