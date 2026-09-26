package me.timschneeberger.rootlessjamesdsp.utils

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.flavor.RootShellImpl

object OnePlus13DecoderDiagnostics {

    data class Snapshot(
        val headline: String,
        val report: String,
        val offloadDetected: Boolean,
        val directDetected: Boolean,
        val decoderCount: Int,
    )

    fun collect(): Snapshot {
        val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { !it.isEncoder }
            .mapNotNull { info ->
                val audioTypes = info.supportedTypes
                    .filter { it.startsWith("audio/", ignoreCase = true) }
                    .sorted()
                if (audioTypes.isEmpty()) null else DecoderInfo(info, audioTypes)
            }
            .sortedWith(
                compareByDescending<DecoderInfo> { it.hardwareAccelerated }
                    .thenByDescending { it.vendor }
                    .thenBy { it.info.name }
            )

        val rootProbe = if (BuildConfig.ONEPLUS13) {
            RootShellImpl.exec(
                """
                echo "c2_preferred=$(getprop vendor.audio.c2.preferred)"
                echo "offload_disable=$(getprop audio.offload.disable)"
                echo "offload_min_duration=$(getprop audio.offload.min.duration.secs)"
                echo "offload_track=$(getprop vendor.audio.offload.track.enable)"
                echo "a2dp_offload=$(getprop vendor.audio.feature.a2dp_offload.enable)"
                echo "component_stores:"
                service list 2>/dev/null | grep 'android.hardware.media.c2.IComponentStore' || true
                echo "active_codec:"
                dumpsys media.codec 2>/dev/null |                   grep -Ei 'c2\.|OMX\.|audio/|decoder|component' | head -240
                echo "media_metrics:"
                dumpsys media.metrics 2>/dev/null |                   grep -Ei 'codec|decoder|audio/|mime|component' | tail -240
                echo "audio_outputs:"
                dumpsys media.audio_policy 2>/dev/null |                   grep -Ei 'compress_offload_out|direct_pcm_out|AUDIO_OUTPUT_FLAG_(DIRECT|COMPRESS_OFFLOAD)|offload' | head -240
                echo "audio_flinger:"
                dumpsys media.audio_flinger 2>/dev/null |                   grep -Ei 'offload|direct|mixer|track|format|sample rate' | head -300
                """.trimIndent()
            )
        } else {
            ""
        }

        val offloadDetected =
            rootProbe.contains("COMPRESS_OFFLOAD", ignoreCase = true) ||
                rootProbe.lineSequence().any {
                    it.contains("offload", ignoreCase = true) &&
                        it.contains("active", ignoreCase = true)
                }

        val directDetected =
            rootProbe.contains("AUDIO_OUTPUT_FLAG_DIRECT", ignoreCase = true) ||
                rootProbe.contains("direct_pcm_out", ignoreCase = true)

        val hardware = codecs.count { it.hardwareAccelerated }
        val software = codecs.count { it.softwareOnly }
        val vendor = codecs.count { it.vendor }
        val c2 = codecs.count { it.info.name.startsWith("c2.", ignoreCase = true) }
        val omx = codecs.count { it.info.name.startsWith("OMX.", ignoreCase = true) }

        val headline = buildString {
            append("${codecs.size} audio decoders")
            append(" · HW $hardware / SW $software")
            append(" · C2 $c2 / OMX $omx")
            if (offloadDetected) append(" · OFFLOAD")
            else if (directDetected) append(" · DIRECT")
            else append(" · PCM/Mixer")
        }

        val report = buildString {
            appendLine("=== Decoder / Codec Inspector ===")
            appendLine("decoderCount=${codecs.size}")
            appendLine("hardwareDecoders=$hardware")
            appendLine("softwareDecoders=$software")
            appendLine("vendorDecoders=$vendor")
            appendLine("codec2Decoders=$c2")
            appendLine("omxDecoders=$omx")
            appendLine("offloadDetected=$offloadDetected")
            appendLine("directDetected=$directDetected")
            appendLine()
            appendLine("=== Decoder inventory ===")
            codecs.forEach { decoder ->
                appendLine(
                    "${decoder.info.name} | " +
                        "hw=${decoder.hardwareAccelerated} " +
                        "sw=${decoder.softwareOnly} " +
                        "vendor=${decoder.vendor} | " +
                        decoder.audioTypes.joinToString()
                )
            }
            if (BuildConfig.ONEPLUS13) {
                appendLine()
                appendLine("=== OnePlus 13 Codec2 / offload probe ===")
                appendLine(rootProbe.ifBlank { "root decoder probe unavailable" })
            }
        }

        return Snapshot(
            headline = headline,
            report = report,
            offloadDetected = offloadDetected,
            directDetected = directDetected,
            decoderCount = codecs.size,
        )
    }

    private data class DecoderInfo(
        val info: MediaCodecInfo,
        val audioTypes: List<String>,
    ) {
        val hardwareAccelerated: Boolean
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isHardwareAccelerated
            } else {
                !softwareOnly
            }

        val softwareOnly: Boolean
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isSoftwareOnly
            } else {
                info.name.startsWith("OMX.google.", ignoreCase = true) ||
                    info.name.startsWith("c2.android.", ignoreCase = true)
            }

        val vendor: Boolean
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isVendor
            } else {
                !softwareOnly
            }
    }
}
