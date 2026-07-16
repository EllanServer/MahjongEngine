package top.ellan.mahjong.gb.jni

import kotlin.test.Test
import kotlin.test.assertTrue

class GbMahjongNativeBridgeSmokeTest {
    @Test
    fun `native bridge responds when library is available`() {
        val bridge = GbMahjongNativeBridge()
        requireGbNativeBridge(bridge)

        val version =
            runCatching { bridge.libraryVersion() }
                .getOrElse { error ->
                    throw AssertionError("Native library loaded but JNI symbols unavailable: ${error.message}", error)
                }
        val ping =
            runCatching { bridge.ping() }
                .getOrElse { error ->
                    throw AssertionError("Native library loaded but ping JNI call failed: ${error.message}", error)
                }

        assertTrue(version.isNotBlank())
        assertTrue(ping.contains("ready"))
    }
}
