package com.coin11.taojinbi.task

object ExternalTaskPolicy {
    private val skippedPackagePrefixes = listOf(
        "com.ss.android.article",
    )

    fun shouldSkipPackage(packageName: String): Boolean =
        skippedPackagePrefixes.any(packageName::startsWith)
}
