package me.timschneeberger.rootlessjamesdsp.utils

import me.timschneeberger.rootlessjamesdsp.flavor.RootShellImpl

object OnePlus13DecoderSources {
    private const val MODULE =
        "/data/adb/modules/oneplus13_jamesdsp_aidl"
    private const val CTL =
        "$MODULE/decoderctl.sh"

    data class Source(
        val id: String,
        val name: String,
        val version: String,
        val ready: Boolean,
        val backend: String,
        val tier: String,
        val activation: String,
        val path: String,
    ) {
        val displayName: String
            get() = buildString {
                append(name.ifBlank { id })
                if (version.isNotBlank()) append(" · $version")
                append(" · $backend")
                append(" · $tier")
                if (!ready) append(" · not ready")
            }
    }

    data class Status(
        val available: Boolean,
        val selected: String,
        val active: String,
        val raw: String,
    )

    data class OperationResult(
        val success: Boolean,
        val output: String,
    )

    fun list(): List<Source> {
        val output = RootShellImpl.exec(
            "if [ -x '$CTL' ]; then sh '$CTL' list; fi"
        )

        return output.lineSequence()
            .mapNotNull(::parseSource)
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<Source> { it.ready }
                    .thenBy { it.tier != "stable" }
                    .thenBy { it.name.lowercase() }
            )
            .toList()
    }

    fun status(): Status {
        val raw = RootShellImpl.exec(
            """
            if [ -x '$CTL' ]; then
              sh '$CTL' status
            else
              echo "module_ctl=missing"
              echo "selected=none"
              echo "active=none"
            fi
            """.trimIndent()
        )

        fun value(key: String): String =
            raw.lineSequence()
                .firstOrNull { it.startsWith("$key=") }
                ?.substringAfter("=")
                ?.trim()
                .orEmpty()

        return Status(
            available = !raw.contains("module_ctl=missing"),
            selected = value("selected").ifBlank { "none" },
            active = value("active").ifBlank { "none" },
            raw = raw,
        )
    }

    fun select(id: String): OperationResult {
        if (!id.matches(Regex("[A-Za-z0-9._-]+"))) {
            return OperationResult(false, "Invalid decoder source id.")
        }

        val source = list().firstOrNull { it.id == id }
            ?: return OperationResult(false, "Decoder source '$id' was not found.")

        if (!source.ready) {
            return OperationResult(
                false,
                "Decoder source '$id' is metadata-only and has no verified payload yet.",
            )
        }

        val output = RootShellImpl.exec(
            "if [ -x '$CTL' ]; then sh '$CTL' select '$id'; else echo '__NO_CTL__'; fi"
        )
        return OperationResult(
            success = !output.contains("__NO_CTL__") &&
                output.contains("Reboot required", ignoreCase = true),
            output = output.trim(),
        )
    }

    fun disable(): OperationResult {
        val output = RootShellImpl.exec(
            "if [ -x '$CTL' ]; then sh '$CTL' disable; else echo '__NO_CTL__'; fi"
        )
        return OperationResult(
            success = !output.contains("__NO_CTL__") &&
                output.contains("Reboot required", ignoreCase = true),
            output = output.trim(),
        )
    }

    fun report(): String {
        val state = status()
        val sources = list()

        return buildString {
            appendLine("=== Decoder Source Mount Layer ===")
            appendLine("controllerAvailable=${state.available}")
            appendLine("selected=${state.selected}")
            appendLine("active=${state.active}")
            appendLine("sourceCount=${sources.size}")
            appendLine()
            sources.forEach { source ->
                appendLine(
                    "${source.id} | ${source.name} | ${source.version} | " +
                        "ready=${source.ready} backend=${source.backend} " +
                        "tier=${source.tier} activation=${source.activation}"
                )
            }
            appendLine()
            appendLine("=== decoderctl status ===")
            appendLine(state.raw)
        }
    }

    private fun parseSource(line: String): Source? {
        val parts = line.split('|')
        if (parts.size < 8) return null

        val id = parts[0].trim()
        if (!id.matches(Regex("[A-Za-z0-9._-]+"))) return null

        fun tagged(prefix: String): String =
            parts.firstOrNull { it.startsWith(prefix) }
                ?.substringAfter(prefix)
                ?.trim()
                .orEmpty()

        return Source(
            id = id,
            name = parts[1].trim(),
            version = parts[2].trim(),
            ready = tagged("ready=") == "1",
            backend = tagged("backend=").ifBlank { "unknown" },
            tier = tagged("tier=").ifBlank { "experimental" },
            activation = tagged("activation=").ifBlank { "unknown" },
            path = parts.last().trim(),
        )
    }
}
