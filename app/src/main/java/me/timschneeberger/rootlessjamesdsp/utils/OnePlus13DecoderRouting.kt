package me.timschneeberger.rootlessjamesdsp.utils

import android.content.Context
import me.timschneeberger.rootlessjamesdsp.flavor.RootShellImpl
import timber.log.Timber

object OnePlus13DecoderRouting {
    const val MODE_AUTO = "auto"
    const val MODE_DSP = "dsp"

    private const val PREFS = "oneplus13_decoder_routing"
    private const val KEY_BASELINE = "offload_disable_baseline"
    private const val EMPTY = "__EMPTY__"

    data class Result(
        val success: Boolean,
        val requestedMode: String,
        val currentValue: String,
        val message: String,
    )

    fun apply(context: Context, mode: String, processingEnabled: Boolean): Result {
        return if (mode == MODE_DSP && processingEnabled) {
            enableDspCompatible(context)
        } else {
            restoreBaseline(context, mode)
        }
    }

    private fun enableDspCompatible(context: Context): Result {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_BASELINE)) {
            val baseline = RootShellImpl.exec("getprop audio.offload.disable").trim()
            prefs.edit()
                .putString(KEY_BASELINE, baseline.ifEmpty { EMPTY })
                .apply()
        }

        val command = """
            if command -v resetprop >/dev/null 2>&1; then
              resetprop audio.offload.disable 1
            else
              setprop audio.offload.disable 1
            fi
            getprop audio.offload.disable
        """.trimIndent()

        val current = RootShellImpl.exec(command)
            .lineSequence()
            .lastOrNull()
            ?.trim()
            .orEmpty()
        val ok = current == "1"

        Timber.i("O13 decoder routing: DSP-compatible requested; offload.disable=%s", current)

        return Result(
            success = ok,
            requestedMode = MODE_DSP,
            currentValue = current,
            message = if (ok) {
                "Compress offload disabled for new tracks; decoded PCM can return to the mixer/effect path."
            } else {
                "Unable to disable compress offload on this root environment."
            },
        )
    }

    private fun restoreBaseline(context: Context, requestedMode: String): Result {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_BASELINE, null)

        if (stored == null) {
            val current = RootShellImpl.exec("getprop audio.offload.disable").trim()
            return Result(
                success = true,
                requestedMode = requestedMode,
                currentValue = current,
                message = "No JamesDSP routing override is active.",
            )
        }

        val value = if (stored == EMPTY) "0" else stored
        val command = """
            if command -v resetprop >/dev/null 2>&1; then
              resetprop audio.offload.disable $value
            else
              setprop audio.offload.disable $value
            fi
            getprop audio.offload.disable
        """.trimIndent()

        val current = RootShellImpl.exec(command)
            .lineSequence()
            .lastOrNull()
            ?.trim()
            .orEmpty()
        val ok = current == value

        if (ok) {
            prefs.edit().remove(KEY_BASELINE).apply()
        }

        Timber.i(
            "O13 decoder routing restored: requested=%s baseline=%s current=%s",
            requestedMode,
            stored,
            current,
        )

        return Result(
            success = ok,
            requestedMode = requestedMode,
            currentValue = current,
            message = if (ok) {
                "Original offload policy restored."
            } else {
                "Failed to restore the original offload policy."
            },
        )
    }
}
