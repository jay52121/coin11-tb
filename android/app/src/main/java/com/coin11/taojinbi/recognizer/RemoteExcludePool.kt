package com.coin11.taojinbi.recognizer

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

data class RemoteExcludePoolApplyResult(
    val rules: RuleSet,
    val source: String,
    val revision: String = "",
    val error: String? = null,
)

data class RemoteExcludePoolRefreshResult(
    val ok: Boolean,
    val revision: String = "",
    val sourceUrl: String = "",
    val changed: Boolean = false,
    val error: String? = null,
)

object RemoteExcludePool {
    private const val CACHE_FILE = "exclude-pool.remote.json"
    private const val PREFS = "exclude_pool_sync"
    private const val CONNECT_TIMEOUT_MS = 2_500
    private const val READ_TIMEOUT_MS = 2_500
    private const val MAX_BYTES = 262_144

    private val sourceUrls = listOf(
        "https://raw.githubusercontent.com/jay52121/coin11-tb/main/cloud/exclude-pool.json",
        "https://cdn.jsdelivr.net/gh/jay52121/coin11-tb@main/cloud/exclude-pool.json",
    )

    fun applyCached(
        context: Context,
        base: RuleSet,
    ): RemoteExcludePoolApplyResult {
        val file = File(context.filesDir, CACHE_FILE)
        if (!file.isFile) {
            return RemoteExcludePoolApplyResult(
                rules = base,
                source = "no remote cache",
            )
        }

        return runCatching {
            val text = file.readText(Charsets.UTF_8)
            val json = JSONObject(text)
            RemoteExcludePoolApplyResult(
                rules = overlayFromJson(base, json),
                source =
                    "remote cache revision=" +
                        json.optString("revision", "?"),
                revision = json.optString("revision", ""),
            )
        }.getOrElse { error ->
            RemoteExcludePoolApplyResult(
                rules = base,
                source = "remote cache invalid",
                error = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    fun refreshAsync(
        context: Context,
        callback: (RemoteExcludePoolRefreshResult) -> Unit,
    ) {
        val appContext = context.applicationContext
        thread(
            name = "exclude-pool-sync",
            isDaemon = true,
        ) {
            callback(refresh(appContext))
        }
    }

    internal fun overlay(
        base: RuleSet,
        schemaVersion: Int,
        coinExcludeTags: List<String>,
        skipTaskExtraWords: List<String>,
    ): RuleSet {
        require(schemaVersion == 1) {
            "unsupported schema_version"
        }

        return base.copy(
            coinExcludeTags = normalize(coinExcludeTags),
            skipTaskExtraWords = normalize(skipTaskExtraWords),
        )
    }

    internal fun overlayFromJson(
        base: RuleSet,
        json: JSONObject,
    ): RuleSet =
        overlay(
            base = base,
            schemaVersion = json.optInt("schema_version", 0),
            coinExcludeTags =
                strings(json, "coin_exclude_tags", required = true),
            skipTaskExtraWords =
                strings(json, "skip_task_extra_words", required = true),
        )

    private fun refresh(context: Context): RemoteExcludePoolRefreshResult {
        var lastError: Throwable? = null
        sourceUrls.forEach { sourceUrl ->
            try {
                val bytes = fetch(withCacheBuster(sourceUrl))
                val text = bytes.toString(Charsets.UTF_8)
                val json = JSONObject(text)
                overlayFromJson(RuleSet.DEFAULT, json)

                val cache = File(context.filesDir, CACHE_FILE)
                val oldHash =
                    if (cache.isFile) {
                        sha256(cache.readBytes())
                    } else {
                        ""
                    }
                val newHash = sha256(bytes)
                val changed = oldHash != newHash

                if (changed) {
                    val tmp = File(context.filesDir, CACHE_FILE + ".tmp")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(cache)) {
                        tmp.copyTo(cache, overwrite = true)
                        tmp.delete()
                    }
                }

                val revision = json.optString("revision", "")
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString("revision", revision)
                    .putString("source_url", sourceUrl)
                    .putLong("last_success_ms", System.currentTimeMillis())
                    .putString("last_error", "")
                    .apply()

                return RemoteExcludePoolRefreshResult(
                    ok = true,
                    revision = revision,
                    sourceUrl = sourceUrl,
                    changed = changed,
                )
            } catch (error: Throwable) {
                lastError = error
            }
        }

        val message =
            lastError?.let {
                it.javaClass.simpleName +
                    (it.message?.let { msg -> ": " + msg } ?: "")
            } ?: "unknown"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong("last_failure_ms", System.currentTimeMillis())
            .putString("last_error", message)
            .apply()

        return RemoteExcludePoolRefreshResult(
            ok = false,
            error = message,
        )
    }

    internal fun withCacheBuster(
        sourceUrl: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): String {
        val separator = if (sourceUrl.contains("?")) "&" else "?"
        return sourceUrl + separator + "v=" + nowMillis
    }

    private fun fetch(sourceUrl: String): ByteArray {
        val connection = URL(sourceUrl).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty(
                "User-Agent",
                "coin11-tb-android-rule-sync/1",
            )
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.connect()

            require(connection.responseCode in 200..299) {
                "HTTP " + connection.responseCode
            }

            connection.inputStream.use { input ->
                val bytes = input.readBytes()
                require(bytes.size <= MAX_BYTES) {
                    "exclude pool too large"
                }
                bytes
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun normalize(values: List<String>): List<String> {
        val seen = linkedSetOf<String>()
        values.forEach { value ->
            val text = value.trim()
            if (text.isNotEmpty()) {
                seen += text
            }
        }
        return seen.toList()
    }

    private fun strings(
        json: JSONObject,
        key: String,
        required: Boolean,
    ): List<String> {
        val array = json.optJSONArray(key)
        if (array == null) {
            require(!required) { key + " is required" }
            return emptyList()
        }

        val seen = linkedSetOf<String>()
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim()
            if (value.isNotEmpty()) {
                seen += value
            }
        }
        return seen.toList()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
}
