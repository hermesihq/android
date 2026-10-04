package io.github.hermesihq.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DeviceMetadataTest {

    @Test
    fun describesTheDevice() {
        val metadata = DeviceMetadata.build(osVersion = "14", appVersion = "3.2.1", extra = emptyMap())

        assertEquals("android", metadata["platform"])
        assertEquals("fcm", metadata["transport"])
        assertEquals("14", metadata["os_version"])
        assertEquals("3.2.1", metadata["app_version"])
        assertEquals("hermesi-android/${BuildConfig.VERSION}", metadata["sdk"])
    }

    @Test
    fun keepsWhatTheAppAdds() {
        val metadata = DeviceMetadata.build("14", "3.2.1", mapOf("plan" to "pro"))

        assertEquals("pro", metadata["plan"])
    }

    @Test
    fun doesNotLetTheAppChangeTheTransportOrThePlatform() {
        // An app that said `apns` would send its Firebase tokens to a provider that cannot reach them, and each
        // refusal would read as a dead device.
        val metadata = DeviceMetadata.build("14", "3.2.1", mapOf("transport" to "apns", "platform" to "ios"))

        assertEquals("fcm", metadata["transport"])
        assertEquals("android", metadata["platform"])
    }

    @Test
    fun leavesOutAnAppVersionItDoesNotHave() {
        assertFalse(DeviceMetadata.build("14", null, emptyMap()).containsKey("app_version"))
    }
}
