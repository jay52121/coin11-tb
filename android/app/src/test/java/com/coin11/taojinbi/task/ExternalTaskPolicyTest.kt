package com.coin11.taojinbi.task

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalTaskPolicyTest {
    @Test
    fun skipsToutiaoPackagesByRuntimePackage() {
        assertTrue(
            ExternalTaskPolicy.shouldSkipPackage("com.ss.android.article.lite"),
        )
        assertTrue(
            ExternalTaskPolicy.shouldSkipPackage("com.ss.android.article.news"),
        )
        assertFalse(
            ExternalTaskPolicy.shouldSkipPackage("com.taobao.taobao"),
        )
    }
}
