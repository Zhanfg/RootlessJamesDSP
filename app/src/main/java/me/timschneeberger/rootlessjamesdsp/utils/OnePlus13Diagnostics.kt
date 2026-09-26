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
    @Volatile private var cachedCoexistLabel = "OPlus?/Dolby?"
    @Volatile private var cachedPolicyEngineState: String? = null
    private val lastBlockCounts = hashMapOf<Int, Int>()

    data class Snapshot(
        val headline: String,
        val report: String,
        val healthy: Boolean,
        val compatibleMode: Boolean,
        val telemetrySupported: Boolean,
        val overloadDetected: Boolean,
    )

    /**
     * Fast UI poll: no root shell, no dumpsys, no filesystem scan.
     * Unsupported vendor parameters simply disable that metric.
     */
    fun collectQuick(routingObserver: RoutingObserver): Snapshot {
        val descriptor = runCatching {
            AudioEffect.queryEffects().orEmpty().firstOrNull { it.uuid == effectUuid }
        }.getOrNull()
        val pluginState = JamesDspRemoteEngine.isPluginInstalled()
        val compatibleMode = pluginState == JamesDspRemoteEngine.PluginState.Compatible
        val dedicatedDriver =
            descriptor?.name?.contains("OnePlus13", ignoreCase = true) == true

        val entries = runCatching {
            MainApplication.instance.rootSessionDatabase.sessionList.entries
                .mapNotNull { entry ->
                    (entry.value as? RemoteEffectSession)?.let { entry.key to it }
                }
        }.getOrDefault(emptyList())

        val engines = entries.mapNotNull { it.second.effect }
        val rates = engines.mapNotNull {
            runCatching { it.sampleRateOrNull }.getOrNull()?.takeIf { rate -> rate > 0 }
        }.distinct()

        var telemetrySupported = false
        var overload = false
        var progressing = false
        var healthProbeBad = false
        var safetySupported = false
        var safetyEnabled = false
        var safetyGainMilliDb: Int? = null
        var clipEvents: Int? = null

        entries.forEach { (sid, session) ->
            val engine = session.effect ?: return@forEach
            if (engine.supportsHealthProbe &&
                (!engine.isPidValid || engine.isSampleRateAbnormal)) {
                healthProbeBad = true
            }

            val blocks = runCatching { engine.processedBlocks }.getOrNull()
            val peak = runCatching { engine.peakMilliDb }.getOrNull()
            val clips = runCatching { engine.clippedSamples }.getOrNull()
            val processUs = runCatching { engine.lastProcessUs }.getOrNull()
            val frames = runCatching { engine.lastProcessedFrames }.getOrNull()
            val rate = runCatching { engine.sampleRateOrNull }.getOrNull()
            val guardEnabled = runCatching { engine.safetyGuardEnabled }.getOrNull()
            val guardGain = runCatching { engine.safetyGainMilliDb }.getOrNull()
            val guardClipEvents = runCatching { engine.clipEvents }.getOrNull()

            if (guardEnabled != null && guardGain != null) {
                safetySupported = true
                safetyEnabled = safetyEnabled || guardEnabled
                safetyGainMilliDb = listOfNotNull(safetyGainMilliDb, guardGain).minOrNull()
                clipEvents = listOfNotNull(clipEvents, guardClipEvents).maxOrNull()
            }

            if (blocks != null && peak != null && processUs != null) {
                telemetrySupported = true
                val old = synchronized(lastBlockCounts) { lastBlockCounts.put(sid, blocks) }
                if (old != null && blocks > old) progressing = true

                val budgetUs =
                    if (frames != null && frames > 0 && rate != null && rate > 0)
                        frames.toDouble() / rate * 1_000_000.0
                    else null
                val loadPct =
                    if (budgetUs != null && processUs >= 0)
                        processUs / budgetUs * 100.0
                    else null

                if ((clips ?: 0) > 0 || peak >= 0 ||
                    (loadPct != null && loadPct >= 100.0)) {
                    overload = true
                }
            }
        }

        synchronized(lastBlockCounts) {
            lastBlockCounts.keys.retainAll(entries.map { it.first }.toSet())
        }

        val driverLabel = when {
            dedicatedDriver -> "Dedicated"
            compatibleMode -> "Compatible"
            pluginState == JamesDspRemoteEngine.PluginState.Available -> "Standard"
            pluginState == JamesDspRemoteEngine.PluginState.Unsupported -> "Unsupported"
            else -> "Unavailable"
        }
        val engineState = when {
            telemetrySupported && progressing -> "Processing"
            healthProbeBad -> "Unhealthy"
            cachedPolicyEngineState == "Bypassed" -> "Bypassed"
            entries.isEmpty() -> "Idle"
            compatibleMode && engines.none { it.supportsHealthProbe } -> "Unverified"
            else -> "Active"
        }
        val route = routingObserver.currentDevice?.name ?: "Unknown"
        val meter = when {
            !telemetrySupported -> "meter n/a"
            overload -> "OVERLOAD"
            else -> "headroom OK"
        }
        val guardLabel = when {
            !safetySupported -> null
            !safetyEnabled -> "Guard OFF"
            (safetyGainMilliDb ?: 0) <= -50 ->
                "Guard ${String.format(java.util.Locale.US, "%.1f", (safetyGainMilliDb ?: 0) / 1000.0)} dB"
            else -> "Guard ON"
        }
        val usable =
            pluginState == JamesDspRemoteEngine.PluginState.Available ||
                pluginState == JamesDspRemoteEngine.PluginState.Compatible

        val headline = buildString {
            append(if (usable && !healthProbeBad) "Healthy" else "Needs attention")
            append(" · $driverLabel · $engineState · $route")
            append(" · $cachedCoexistLabel")
            if (rates.isNotEmpty()) append(" · ${rates.joinToString("/")} Hz")
            append(" · $meter")
            guardLabel?.let { append(" · $it") }
            append(" · ${entries.size} session")
            if (entries.size != 1) append('s')
        }

        return Snapshot(
            headline = headline,
            report = headline,
            healthy = usable && !healthProbeBad,
            compatibleMode = compatibleMode,
            telemetrySupported = telemetrySupported,
            overloadDetected = overload,
        )
    }

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
            val guardEnabled = runCatching { engine.safetyGuardEnabled }.getOrNull()
            val guardGainMilliDb = runCatching { engine.safetyGainMilliDb }.getOrNull()
            val clipEvents = runCatching { engine.clipEvents }.getOrNull()
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
                safetyGuardEnabled = guardEnabled,
                safetyGainMilliDb = guardGainMilliDb,
                clipEvents = clipEvents,
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
        val safetyGuardSupported = telemetry.any {
            it.safetyGuardEnabled != null && it.safetyGainMilliDb != null
        }
        val safetyGuardEnabled = telemetry.any { it.safetyGuardEnabled == true }
        val safetyGainMilliDb = telemetry.mapNotNull { it.safetyGainMilliDb }.minOrNull()
        val clipEvents = telemetry.mapNotNull { it.clipEvents }.maxOrNull()
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
                if [ -d /data/adb/modules/ainur_jamesdsp ] &&                    [ ! -f /data/adb/modules/ainur_jamesdsp/disable ] &&                    [ ! -f /data/adb/modules/ainur_jamesdsp/remove ]; then
                  echo "legacy_ainur=active"
                else
                  echo "legacy_ainur=inactive"
                fi
                echo "james_effect_instances=$(dumpsys media.audio_policy 2>/dev/null | grep -Ei 'James.*DSP|JamesDSP' | grep -Ec 'Effect ID:|Music Effect\\?' || true)"
                echo "james_effect_enabled=$(dumpsys media.audio_policy 2>/dev/null | grep -Ei 'James.*DSP|JamesDSP' | grep -c 'Enabled' || true)"
                echo "james_effect_disabled=$(dumpsys media.audio_policy 2>/dev/null | grep -Ei 'James.*DSP|JamesDSP' | grep -c 'Disabled' || true)"
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
        val legacyAinurActive = probeValue("legacy_ainur") == "active"
        val jamesEffectInstances =
            probeValue("james_effect_instances")?.toIntOrNull() ?: 0
        val jamesEnabledInstances =
            probeValue("james_effect_enabled")?.toIntOrNull() ?: 0
        val jamesDisabledInstances =
            probeValue("james_effect_disabled")?.toIntOrNull() ?: 0

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
        val safetyLabel = when {
            !safetyGuardSupported -> null
            !safetyGuardEnabled -> "Guard OFF"
            (safetyGainMilliDb ?: 0) <= -50 ->
                "Guard ${String.format(java.util.Locale.US, "%.1f", (safetyGainMilliDb ?: 0) / 1000.0)} dB"
            else -> "Guard ON"
        }

        val engineState = when {
            jamesEffectInstances > 0 && jamesEnabledInstances == 0 -> "Bypassed"
            sessionEntries.isEmpty() -> "Idle"
            liveEngines.any { it.supportsHealthProbe } &&
                liveEngines.all { it.isPidValid && !it.isSampleRateAbnormal } -> "Alive"
            compatibleMode -> "Unverified"
            else -> "Active"
        }
        cachedPolicyEngineState = engineState

        val coexistLabel = buildString {
            append("OPlus")
            append(if (oplusPresent) "✓" else "?")
            append("/Dolby")
            append(if (dolbyPresent) "✓" else "?")
            if (spatialPresent) append("/Spatial✓")
        }
        cachedCoexistLabel = coexistLabel

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
            safetyLabel?.let { append(" · $it") }
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
            appendLine("safetyGuardSupported=$safetyGuardSupported")
            appendLine("safetyGuardEnabled=${if (safetyGuardSupported) safetyGuardEnabled else "unsupported"}")
            appendLine("safetyGainMilliDb=${safetyGainMilliDb ?: "unsupported"}")
            appendLine("clipEvents=${clipEvents ?: "unsupported"}")
            appendLine("controlProbeUs=${controlProbeUs ?: "unknown"}")
            appendLine("overloadDetected=$overloadDetected")
            appendLine("oplusAudioPresent=$oplusPresent")
            appendLine("dolbyPresent=$dolbyPresent")
            appendLine("spatialPresent=$spatialPresent")
            appendLine("legacyAinurModuleActive=$legacyAinurActive")
            appendLine("jamesEffectInstances=$jamesEffectInstances")
            appendLine("jamesEnabledInstances=$jamesEnabledInstances")
            appendLine("jamesDisabledInstances=$jamesDisabledInstances")

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

    fun collectSessionReport(context: Context): String {
        val entries = runCatching {
            MainApplication.instance.rootSessionDatabase.sessionList.entries
                .mapNotNull { entry ->
                    (entry.value as? RemoteEffectSession)?.let { entry.key to it }
                }
                .sortedBy { it.first }
        }.getOrDefault(emptyList())

        if (entries.isEmpty()) {
            return "No active JamesDSP sessions."
        }

        val pm = context.packageManager
        return buildString {
            appendLine("Active sessions: ${entries.size}")
            entries.forEachIndexed { index, (sessionId, session) ->
                val label = runCatching {
                    val info = pm.getApplicationInfo(session.packageName, 0)
                    pm.getApplicationLabel(info).toString()
                }.getOrDefault(session.packageName)
                val engine = session.effect

                appendLine()
                appendLine("#${index + 1}  $label")
                appendLine("package=${session.packageName}")
                appendLine("sessionId=$sessionId")
                appendLine("uid=${session.uid}")
                if (engine == null) {
                    appendLine("engine=detached")
                } else {
                    appendLine("pid=${engine.pidOrNull ?: "unsupported"}")
                    appendLine("sampleRate=${engine.sampleRateOrNull ?: "unsupported"}")
                    appendLine("healthProbe=${engine.supportsHealthProbe}")
                    appendLine("processedBlocks=${engine.processedBlocks ?: "unsupported"}")
                    appendLine("peakMilliDb=${engine.peakMilliDb ?: "unsupported"}")
                    appendLine("clipEvents=${engine.clipEvents ?: "unsupported"}")
                    appendLine("safetyGuard=${engine.safetyGuardEnabled ?: "unsupported"}")
                    appendLine("safetyGainMilliDb=${engine.safetyGainMilliDb ?: "unsupported"}")
                }
            }
        }
    }

    fun collectCapabilityReport(): String {
        val descriptor = runCatching {
            AudioEffect.queryEffects().orEmpty().firstOrNull { it.uuid == effectUuid }
        }.getOrNull()
        val state = JamesDspRemoteEngine.isPluginInstalled()
        val entries = runCatching {
            MainApplication.instance.rootSessionDatabase.sessionList.values
                .filterIsInstance<RemoteEffectSession>()
        }.getOrDefault(emptyList())
        val engines = entries.mapNotNull { it.effect }

        val dedicated =
            descriptor?.name?.contains("OnePlus13", ignoreCase = true) == true
        val basicControl =
            state == JamesDspRemoteEngine.PluginState.Available ||
                state == JamesDspRemoteEngine.PluginState.Compatible

        fun activeProbe(result: Boolean, supported: Boolean): String = when {
            engines.isEmpty() -> "Not probed (no active session)"
            supported && result -> "Available"
            supported -> "Reported unhealthy"
            else -> "Unavailable / not exposed"
        }

        val healthSupported = engines.any { it.supportsHealthProbe }
        val healthOk = engines.isNotEmpty() &&
            engines.filter { it.supportsHealthProbe }
                .all { it.isPidValid && !it.isSampleRateAbnormal }
        val telemetrySupported = engines.any { it.supportsOnePlus13Telemetry }
        val safetySupported = engines.any { it.supportsSafetyGuard }

        return buildString {
            appendLine("Driver: ${descriptor?.name ?: "not registered"}")
            appendLine("Implementor: ${descriptor?.implementor ?: "unknown"}")
            appendLine("Mode: ${when {
                dedicated -> "Dedicated OnePlus 13"
                state == JamesDspRemoteEngine.PluginState.Compatible -> "Compatible"
                state == JamesDspRemoteEngine.PluginState.Available -> "Standard"
                state == JamesDspRemoteEngine.PluginState.Unsupported -> "Unsupported"
                else -> "Unavailable"
            }}")
            appendLine()
            appendLine("Core JamesDSP control: ${if (basicControl) "Available" else "Unavailable"}")
            appendLine("PID/sample-rate health probe: ${activeProbe(healthOk, healthSupported)}")
            appendLine(
                "Realtime peak/process telemetry: " +
                    if (engines.isEmpty()) "Not probed (no active session)"
                    else if (telemetrySupported) "Available" else "Unavailable / not exposed"
            )
            appendLine(
                "Adaptive Safety Guard: " +
                    if (engines.isEmpty()) "Not probed (no active session)"
                    else if (safetySupported) "Available" else "Unavailable / not exposed"
            )
            appendLine(
                "Automatic route recovery: " +
                    when {
                        engines.isEmpty() -> "Not probed (no active session)"
                        healthSupported -> "Full"
                        basicControl -> "Limited (no health probe)"
                        else -> "Unavailable"
                    }
            )
            appendLine("Convolver sample-rate refresh: ${if (basicControl) "Available" else "Unavailable"}")
            appendLine()
            appendLine(
                "Compatibility policy: non-dedicated drivers remain usable; " +
                    "only unsupported extensions are disabled."
            )
        }
    }

    private data class Telemetry(
        val peakMilliDb: Int?,
        val clippedSamples: Int?,
        val processUs: Int?,
        val maxProcessUs: Int?,
        val frames: Int?,
        val blocks: Int?,
        val safetyGuardEnabled: Boolean?,
        val safetyGainMilliDb: Int?,
        val clipEvents: Int?,
        val probeUs: Int,
    )
}
