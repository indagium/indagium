package com.openlog

import com.openlog.generated.BuildInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class AppBuildInfoTest {
    @Test
    fun exposesPackagedAppVersionToRuntime() {
        assertEquals("1.0.0", BuildInfo.APP_VERSION)
    }
}
