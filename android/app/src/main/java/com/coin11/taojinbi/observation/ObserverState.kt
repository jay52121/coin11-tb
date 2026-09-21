package com.coin11.taojinbi.observation

import java.util.concurrent.CopyOnWriteArraySet

object ObserverState {
    private val listeners = CopyOnWriteArraySet<(Observation?) -> Unit>()

    @Volatile
    var latestExternalObservation: Observation? = null
        private set

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
