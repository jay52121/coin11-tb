package com.coin11.taojinbi.recognizer

import android.content.Context
import org.json.JSONObject

data class LoadedRules(
    val rules: RuleSet,
    val source: String,
    val error: String? = null,
)

object RulesLoader {
    fun load(context: Context): LoadedRules {
        return runCatching {
            val text = context.assets.open("rules.json")
                .bufferedReader()
                .use { it.readText() }

            LoadedRules(
                rules = RuleSet.fromJson(JSONObject(text)),
                source = "assets/rules.json",
            )
        }.getOrElse { error ->
            LoadedRules(
                rules = RuleSet.DEFAULT,
                source = "built-in fallback",
                error = error.message ?: error.javaClass.simpleName,
            )
        }
    }
}
