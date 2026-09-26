package com.coin11.taojinbi.actions

data class PendingActionRequest(
    val id: Long,
    val type: PendingActionType,
    val targetPackage: String,
    val armedAtMillis: Long,
    val expiresAtMillis: Long,
)

enum class PendingActionType {
    TAP_CENTER,
    SWIPE_UP,
    BACK,
}

object PendingActionState {
    private const val DEFAULT_TTL_MS = 15_000L
    private var nextId = 1L

    @Volatile
    private var pending: PendingActionRequest? = null

    @Synchronized
    fun arm(
        type: PendingActionType,
        targetPackage: String,
        nowMillis: Long = System.currentTimeMillis(),
        ttlMillis: Long = DEFAULT_TTL_MS,
    ): PendingActionRequest {
        val request = PendingActionRequest(
            id = nextId++,
            type = type,
            targetPackage = targetPackage,
            armedAtMillis = nowMillis,
            expiresAtMillis = nowMillis + ttlMillis,
        )
        pending = request
        return request
    }

    @Synchronized
    fun consumeIf(
        nowMillis: Long = System.currentTimeMillis(),
        predicate: (PendingActionRequest) -> Boolean,
    ): PendingActionRequest? {
        val request = pending ?: return null
        if (nowMillis > request.expiresAtMillis) {
            pending = null
            return null
        }
        if (!predicate(request)) {
            return null
        }
        pending = null
        return request
    }

    @Synchronized
    fun clear() {
        pending = null
    }

    @Synchronized
    fun describe(nowMillis: Long = System.currentTimeMillis()): String {
        val request = pending ?: return "(none)"
        if (nowMillis > request.expiresAtMillis) {
            pending = null
            return "(expired)"
        }
        return "#" + request.id + " " + request.type + " -> " + request.targetPackage
    }
}
