package top.ellan.mahjong.gb.jni

import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.assertTrue

internal fun requireGbNativeBridge(bridge: GbMahjongNativeBridge) {
    val detail = bridge.availabilityDetail()
    if (System.getProperty("mahjong.test.requireNative", "false").toBoolean()) {
        assertTrue(bridge.isAvailable(), detail)
    } else {
        assumeTrue(bridge.isAvailable(), detail)
    }
}
