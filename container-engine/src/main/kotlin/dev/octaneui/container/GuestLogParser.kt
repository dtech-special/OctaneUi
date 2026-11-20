package dev.octaneui.container

import dev.octaneui.core.LogLevel

/** Parsing of guest.log: the level comes from the prefix our scripts write; everything else is tool output (WARN). */
object GuestLogParser {
    data class Line(val level: LogLevel, val text: String)

    fun parse(lines: List<String>): List<Line> = lines.filter { it.isNotBlank() }.map { l ->
        val t = l.trim()
        when {
            t.startsWith("[octaneui] step:") -> Line(LogLevel.INFO, t.removePrefix("[octaneui] step:").trim())
            t.startsWith("[octaneui] warn:") -> Line(LogLevel.WARN, t.removePrefix("[octaneui] warn:").trim())
            t.startsWith("[octaneui] error:") -> Line(LogLevel.ERROR, t.removePrefix("[octaneui] error:").trim())
            t.startsWith("[octaneui] mount:") -> Line(LogLevel.WARN, "mount: " + t.removePrefix("[octaneui] mount:").trim())
            else -> Line(LogLevel.WARN, t)
        }
    }
}
