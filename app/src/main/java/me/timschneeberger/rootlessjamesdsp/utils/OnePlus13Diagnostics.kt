package me.timschneeberger.rootlessjamesdsp.utils

import android.content.Context
import android.media.audiofx.AudioEffect
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.flavor.RootShellImpl
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspRemoteEngine
import me.timschneeberger.rootlessjamesdsp.model.root.RemoteEffectSession
import java.util.UUID

object OnePlus13Diagnostics {
    private val effectUuid = UUID.fromString("f27317f4-c984-4de6-9a90-545759495bf2")

    data class Snapshot(
        val headline: String,
        val report: String,
        val healthy: Boolean,
    )

    fun collect(context: Context, routingObserver: RoutingObserver): Snapshot {
        val descriptor = runCatching {
            AudioEffect.queryEffects().orEmpty().firstOrNull { it.uuid == effectUuid }
        }.getOrNull()

        val pluginState = JamesDspRemoteEngine.isPluginInstalled()

        val sessions = runCatching {
            MainApplication.instance.rootSessionDatabase.sessionList
                .values
                .filterIsInstance<RemoteEffectSession>()
                .toList()
        }.getOrDefault(emptyList())

        val liveEngines = sessions.mapNotNull { it.effect }
        val sampleRates = liveEngines.mapNotNull {
            runCatching { it.sampleRate.toInt() }.getOrNull()?.takeIf { rate -> rate > 0 }
        }.distinct()
        val pids = liveEngines.mapNotNull {
            runCatching { it.pid }.getOrNull()?.takeIf { pid -> pid > 0 }
        }.distinct()
        val commitCounts = liveEngines.mapNotNull {
            runCatching { it.paramCommitCount }.getOrNull()?.takeIf { count -> count >= 0 }
        }

        val route = routingObserver.currentDevice?.name ?: "Unknown"
        val serviceProbe = if (BuildConfig.ONEPLUS13) RootShellImpl.exec(
            """
            echo "factory=$(service list 2>/dev/null | grep -c 'android.hardware.audio.effect.IFactory/default')"
            echo "audioserver=$(pidof audioserver 2>/dev/null)"
            echo "audiohal=$(pidof audiohalservice.qti 2>/dev/null)"
            echo "module_lib=$(test -r /odm/lib64/soundfx/libjamesdsp_aidl.so && echo present || echo missing)"
            echo "effect_registration:"
            grep -H -i -E 'f27317f4-c984-4de6-9a90-545759495bf2|libjamesdsp_aidl\.so'               /odm/etc/audio_effects_config.xml               /vendor/etc/audio/sku_sun/audio_effects_config.xml 2>/dev/null | head -80
            echo "vendor_effects:"
            find /odm/lib64/soundfx /vendor/lib64/soundfx -maxdepth 1 -type f               \( -iname '*oplus*' -o -iname '*dolby*' -o -iname '*spatial*' \)               -print 2>/dev/null | sort | head -120
            """.trimIndent()
        ) else ""

        val factoryOk = serviceProbe.lineSequence()
            .firstOrNull { it.startsWith("factory=") }
            ?.substringAfter("=")?.trim()?.toIntOrNull()?.let { it > 0 } ?: !BuildConfig.ONEPLUS13
        val audioserverOk = serviceProbe.lineSequence()
            .firstOrNull { it.startsWith("audioserver=") }
            ?.substringAfter("=")?.trim()?.isNotBlank() ?: !BuildConfig.ONEPLUS13
        val audioHalOk = serviceProbe.lineSequence()
            .firstOrNull { it.startsWith("audiohal=") }
            ?.substringAfter("=")?.trim()?.isNotBlank() ?: !BuildConfig.ONEPLUS13
        val moduleLibOk = serviceProbe.lineSequence()
            .firstOrNull { it.startsWith("module_lib=") }
            ?.substringAfter("=")?.trim() == "present" || !BuildConfig.ONEPLUS13

        val compatibleDriver = descriptor?.name?.contains("OnePlus13", ignoreCase = true) == true
        val healthy = pluginState == JamesDspRemoteEngine.PluginState.Available &&
            (!BuildConfig.ONEPLUS13 || (factoryOk && audioserverOk && audioHalOk && moduleLibOk && compatibleDriver))

        val sessionSummary = if (sessions.isEmpty()) {
            "0"
        } else {
            sessions.joinToString(prefix = "${sessions.size} (", postfix = ")") { it.packageName }
        }

        val headline = buildString {
            append(if (healthy) "Healthy" else "Needs attention")
            append(" · ")
            append(descriptor?.name ?: "JamesDSP effect not registered")
            append(" · ")
            append(route)
            if (sampleRates.isNotEmpty()) append(" · ${sampleRates.joinToString("/")} Hz")
            append(" · ${sessions.size} session")
            if (sessions.size != 1) append('s')
        }

        val report = buildString {
            appendLine("=== OnePlus 13 JamesDSP diagnostics ===")
            appendLine("controllerBuild=${if (BuildConfig.ONEPLUS13) "oneplus13" else "generic"}")
            appendLine("applicationId=${BuildConfig.APPLICATION_ID}")
            appendLine("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("commit=${BuildConfig.COMMIT_SHA}")
            appendLine("effectState=$pluginState")
            appendLine("effectName=${descriptor?.name ?: "missing"}")
            appendLine("effectImplementor=${descriptor?.implementor ?: "missing"}")
            appendLine("effectUuid=${descriptor?.uuid ?: "missing"}")
            appendLine("onePlus13DriverNameMatch=$compatibleDriver")
            appendLine("route=$route")
            appendLine("sessions=$sessionSummary")
            appendLine("enginePids=${if (pids.isEmpty()) "none" else pids.joinToString()}")
            appendLine("sampleRates=${if (sampleRates.isEmpty()) "unknown" else sampleRates.joinToString()}")
            appendLine("paramCommits=${if (commitCounts.isEmpty()) "unknown" else commitCounts.joinToString()}")
            if (BuildConfig.ONEPLUS13) {
                appendLine()
                appendLine("=== SM8750 / ColorOS audio chain probe ===")
                appendLine(serviceProbe.ifBlank { "root probe unavailable" })
            }
        }

        return Snapshot(headline, report, healthy)
    }
}
