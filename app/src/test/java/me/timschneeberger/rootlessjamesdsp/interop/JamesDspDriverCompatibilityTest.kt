package me.timschneeberger.rootlessjamesdsp.interop

import org.junit.Assert.assertEquals
import org.junit.Test

class JamesDspDriverCompatibilityTest {

    @Test
    fun dedicatedOnePlus13DriverIsAvailable() {
        assertEquals(
            JamesDspRemoteEngine.PluginState.Available,
            JamesDspRemoteEngine.classifyPluginName(
                "JamesDSP OnePlus13 AIDL",
                onePlus13Build = true,
            ),
        )
    }

    @Test
    fun genericJamesDspDriverFallsBackToCompatibleOnOnePlus13() {
        assertEquals(
            JamesDspRemoteEngine.PluginState.Compatible,
            JamesDspRemoteEngine.classifyPluginName(
                "JamesDSP",
                onePlus13Build = true,
            ),
        )
    }

    @Test
    fun otherForkDriverFallsBackToCompatibleOnOnePlus13() {
        assertEquals(
            JamesDspRemoteEngine.PluginState.Compatible,
            JamesDspRemoteEngine.classifyPluginName(
                "RootlessZachDSP",
                onePlus13Build = true,
            ),
        )
    }

    @Test
    fun knownV3ProtocolRemainsUnsupported() {
        assertEquals(
            JamesDspRemoteEngine.PluginState.Unsupported,
            JamesDspRemoteEngine.classifyPluginName(
                "JamesDSP v3",
                onePlus13Build = true,
            ),
        )
    }

    @Test
    fun genericBuildKeepsNormalDriverAvailable() {
        assertEquals(
            JamesDspRemoteEngine.PluginState.Available,
            JamesDspRemoteEngine.classifyPluginName(
                "JamesDSP",
                onePlus13Build = false,
            ),
        )
    }
}
