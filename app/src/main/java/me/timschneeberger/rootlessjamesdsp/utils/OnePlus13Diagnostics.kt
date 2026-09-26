package me.timschneeberger.rootlessjamesdsp.utils

import android.content.Context
import android.media.audiofx.AudioEffect
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.flavor.RootShellImpl
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspRemoteEngine
import me.timschneeberger.rootlessjamesdsp.model.root.RemoteEffectSession
import java.util.UUID
import kotlin.math.roundToInt

object OnePlus13Diagnostics {
    private val effectUuid = UUID.fromString("f27317f4-c984-4de6-9a90-545759495bf2")

    data class Snapshot(
        val headline: String,
        val report: String,
        val healthy: Boolean,
        val compatibleMode: Boolean,
        val telemetrySupported: Boolean,
        val overloadDetected: Boolean,
    )

    fun collect(context: Context, routingObserver: RoutingObserver): Snapshot {
        val descriptor = runCatching {
            AudioEffect.queryEffects().orEmpty().firstOrNull { it.uuid == effectUuid }
        }.getOrNull()

        val pluginState = JamesDspRemoteEngine.isPluginInstalled()
        val compatibleMode = pluginState == JamesDspRemoteEngine.PluginState.Compatible
        val dedicatedDriver =
            descriptor?.name?.contains("OnePlus13", ignoreCase = true) == true

        val sessionEntries = runCatching {
            MainApplication.instance.rootSessionDatabase.sessionList.entries
                .mapNotNull { entry ->
                    (entry.value as? RemoteEffectSession)?.let { entry.key to it }
                }
        }.getOrDefault(emptyList())

        val sessions = sessionEntries.map { it.second }
        val liveEngines = sessions.mapNotNull { it.effect }

        val sampleRates = liveEngines.mapNotNull {
            runCatching { it.sampleRateOrNull }.getOrNull()
                ?.takeIf { rate -> rate > 0 }
        }.distinct()

        val pids = liveEngines.mapNotNull {
            runCatching { it.pid }.getOrNull()
                ?.takeIf { pid -> pid > 0 }
        }.distinct()

        val commitCounts = liveEngines.mapNotNull {
            runCatching { it.paramCommitCount }.getOrNull()
                ?.takeIf { count -> count >= 0 }
        }

        val telemetry = liveEngines.map { engine ->
            val started = System.nanoTime()
            val peak = runCatching { engine.peakMilliDb }.getOrNull()
            val clips = runCatching { engine.clippedSamples }.getOrNull()
            val processUs = runCatching { engine.lastProcessUs }.getOrNull()
            val maxProcessUs = runCatching { engine.maxProcessUs }.getOrNull()
            val frames = runCatching { engine.lastProcessedFrames }.getOrNull()
            val blocks = runCatching { engine.processedBlocks }.getOrNull()
            val probeUs = ((System.nanoTime() - started) / 1000L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()

            Telemetry(
                peakMilliDb = peak,
                clippedSamples = clips,
                processUs = processUs,
                maxProcessUs = maxProcessUs,
                frames = frames,
                blocks = blocks,
                probeUs = probeUs,
            )
        }

        val telemetrySupported = telemetry.any {
            it.peakMilliDb != null &&
                it.processUs != null &&
                it.blocks != null
        }

        val peakMilliDb = telemetry.mapNotNull { it.peakMilliDb }.maxOrNull()
        val clippedSamples = telemetry.mapNotNull { it.clippedSamples }.sum()
        val lastProcessUs = telemetry.mapNotNull { it.processUs }.maxOrNull()
        val maxProcessUs = telemetry.mapNotNull { it.maxProcessUs }.maxOrNull()
        val processedFrames = telemetry.mapNotNull { it.frames }.maxOrNull()
        val processedBlocks = telemetry.mapNotNull { it.blocks }.maxOrNull()
        val controlProbeUs = telemetry.map { it.probeUs }.maxOrNull()

        val firstRate = sampleRates.firstOrNull()
        val processBudgetUs =
            if (processedFrames != null && processedFrames > 0 &&
                firstRate != null && firstRate > 0
            ) {
                processedFrames.toDouble() / firstRate * 1_000_000.0
            } else {
                null
            }

        val processLoadPct =
            if (processBudgetUs != null && lastProcessUs != null) {
                lastProcessUs / processBudgetUs * 100.0
            } else {
                null
            }

        val overloadDetected =
            clippedSamples > 0 ||
                (peakMilliDb != null && peakMilliDb >= 0) ||
                (processLoadPct != null && processLoadPct >= 100.0)

        val route = routingObserver.currentDevice?.name ?: "Unknown"

        val serviceProbe = if (BuildConfig.ONEPLUS13) {
            RootShellImpl.exec(
                """
                echo "factory=$(service list 2>/dev/null | grep -c 'android.hardware.audio.effect.IFactory/default')"
                echo "audioserver=$(pidof audioserver 2>/dev/null)"
                echo "audiohal=$(pidof audiohalservice.qti 2>/dev/null)"
                echo "module_lib=$(test -r /odm/lib64/soundfx/libjamesdsp_aidl.so && echo present || echo missing)"
                echo "effect_registration:"
                grep -H -i -E 'f27317f4-c984-4de6-9a90-545759495bf2|libjamesdsp_aidl\.so' \
                  /odm/etc/audio_effects_config.xml \
                  /vendor/etc/audio/sku_sun/audio_effects_config.xml 2>/dev/null | head -80
                echo "vendor_effects:"
                find /odm/lib64/soundfx /vendor/lib64/soundfx /system/lib64/soundfx \
                  -maxdepth 1 -type f \
                  \( -iname '*oplus*' -o -iname '*dolby*' -o -iname '*spatial*' \) \
                  -print 2>/dev/null | sort | head -120
                echo "runtime_effects:"
                dumpsys media.audio_flinger 2>/dev/null | \
                  grep -Ei 'jamesdsp|dolby|oplus|spatial' | head -160
                """.trimIndent()
            )
        } else {
            ""
        }

        fun probeValue(key: String): String? =
            serviceProbe.lineSequence()
                .firstOrNull { it.startsWith("$key=") }
                ?.substringAfter("=")
                ?.trim()

        val factoryOk =
            probeValue("factory")?.toIntOrNull()?.let { it > 0 }
                ?: !BuildConfig.ONEPLUS13
        val audioserverOk =
            probeValue("audioserver")?.isNotBlank()
                ?: !BuildConfig.ONEPLUS13
        val audioHalOk =
            probeValue("audiohal")?.isNotBlank()
                ?: !BuildConfig.ONEPLUS13
        val moduleLibOk = probeValue("module_lib") == "present"

        val oplusPresent = serviceProbe.contains("oplus", ignoreCase = true)
        val dolbyPresent = serviceProbe.contains("dolby", ignoreCase = true)
        val spatialPresent = serviceProbe.contains("spatial", ignoreCase = true)

        val compatibleProtocol =
            pluginState == JamesDspRemoteEngine.PluginState.Available ||
                pluginState == JamesDspRemoteEngine.PluginState.Compatible

        // External compatible drivers do not need our /odm module library.
        val driverPresentOk =
            if (dedicatedDriver) {
                moduleLibOk || descriptor != null
            } else {
                descriptor != null
            }

        val healthy =
            compatibleProtocol &&
                (!BuildConfig.ONEPLUS13 ||
                    (factoryOk && audioserverOk && audioHalOk && driverPresentOk))

        val sessionSummary =
            if (sessionEntries.isEmpty()) {
                "0"
            } else {
                sessionEntries.joinToString(
                    prefix = "${sessionEntries.size} (",
                    postfix = ")"
                ) { "${it.first}:${it.second.packageName}" }
            }

        val driverLabel = when {
            dedicatedDriver -> "Dedicated"
            compatibleMode -> "Compatible"
            pluginState == JamesDspRemoteEngine.PluginState.Available -> "Standard"
            pluginState == JamesDspRemoteEngine.PluginState.Unsupported -> "Unsupported"
            else -> "Unavailable"
        }

        val overloadLabel = when {
            !telemetrySupported -> "meter n/a"
            overloadDetected -> "OVERLOAD"
            peakMilliDb != null && peakMilliDb >= -1000 -> "near 0 dBFS"
            else -> "headroom OK"
        }

        val engineState = when {
            sessionEntries.isEmpty() -> "Idle"
            liveEngines.any { it.supportsHealthProbe } &&
                liveEngines.all { it.isPidValid && !it.isSampleRateAbnormal } -> "Alive"
            compatibleMode -> "Unverified"
            else -> "Active"
        }

        val coexistLabel = buildString {
            append("OPlus")
            append(if (oplusPresent) "✓" else "?")
            append("/Dolby")
            append(if (dolbyPresent) "✓" else "?")
            if (spatialPresent) append("/Spatial✓")
        }

        val headline = buildString {
            append(if (healthy) "Healthy" else "Needs attention")
            append(" · $driverLabel")
            append(" · $engineState")
            append(" · $route")
            append(" · $coexistLabel")
            if (sampleRates.isNotEmpty()) {
                append(" · ${sampleRates.joinToString("/")} Hz")
            }
            append(" · $overloadLabel")
            append(" · ${sessionEntries.size} session")
            if (sessionEntries.size != 1) append('s')
        }

        val peakText =
            peakMilliDb?.let { "${it / 1000.0} dBFS" } ?: "unsupported"

        val processText =
            if (lastProcessUs != null) {
                buildString {
                    append("$lastProcessUs us")
                    processLoadPct?.let {
                        append(" (${(it * 10).roundToInt() / 10.0}% of block budget)")
                    }
                }
            } else {
                "unsupported"
            }

        val report = buildString {
            appendLine("=== OnePlus 13 JamesDSP diagnostics ===")
            appendLine(
                "controllerBuild=" +
                    if (BuildConfig.ONEPLUS13) "oneplus13" else "generic"
            )
            appendLine("applicationId=${BuildConfig.APPLICATION_ID}")
            appendLine("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("commit=${BuildConfig.COMMIT_SHA}")
            appendLine("driverMode=$driverLabel")
            appendLine("effectState=$pluginState")
            appendLine("effectName=${descriptor?.name ?: "missing"}")
            appendLine("effectImplementor=${descriptor?.implementor ?: "missing"}")
            appendLine("effectUuid=${descriptor?.uuid ?: "missing"}")
            appendLine("dedicatedOnePlus13Driver=$dedicatedDriver")
            appendLine("compatibleFallback=$compatibleMode")
            appendLine("route=$route")
            appendLine("engineState=$engineState")
            appendLine("coexistence=$coexistLabel")
            appendLine("sessions=$sessionSummary")
            sessionEntries.forEach { (sid, session) ->
                appendLine("session[$sid]=${session.packageName} uid=${session.uid}")
            }
            appendLine(
                "enginePids=" +
                    if (pids.isEmpty()) "none" else pids.joinToString()
            )
            appendLine(
                "sampleRates=" +
                    if (sampleRates.isEmpty()) "unknown" else sampleRates.joinToString()
            )
            appendLine(
                "paramCommits=" +
                    if (commitCounts.isEmpty()) "unknown" else commitCounts.joinToString()
            )
            appendLine("telemetrySupported=$telemetrySupported")
            appendLine("lastPeak=$peakText")
            appendLine(
                "lastClippedSamples=" +
                    if (telemetrySupported) clippedSamples else "unsupported"
            )
            appendLine("lastProcessTime=$processText")
            appendLine("maxProcessUs=${maxProcessUs ?: "unsupported"}")
            appendLine("processedFrames=${processedFrames ?: "unsupported"}")
            appendLine("processedBlocks=${processedBlocks ?: "unsupported"}")
            appendLine("controlProbeUs=${controlProbeUs ?: "unknown"}")
            appendLine("overloadDetected=$overloadDetected")
            appendLine("oplusAudioPresent=$oplusPresent")
            appendLine("dolbyPresent=$dolbyPresent")
            appendLine("spatialPresent=$spatialPresent")

            if (BuildConfig.ONEPLUS13) {
                appendLine()
                appendLine("=== SM8750 / ColorOS audio chain probe ===")
                appendLine(serviceProbe.ifBlank { "root probe unavailable" })
            }
        }

        return Snapshot(
            headline = headline,
            report = report,
            healthy = healthy,
            compatibleMode = compatibleMode,
            telemetrySupported = telemetrySupported,
            overloadDetected = overloadDetected,
        )
    }

    private data class Telemetry(
        val peakMilliDb: Int?,
        val clippedSamples: Int?,
        val processUs: Int?,
        val maxProcessUs: Int?,
        val frames: Int?,
        val blocks: Int?,
        val probeUs: Int,
    )
}
