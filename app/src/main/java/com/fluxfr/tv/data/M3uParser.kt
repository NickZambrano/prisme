package com.fluxfr.tv.data

class M3uEntry(val name: String, val group: String, val logo: String?, val url: String)

/** Lecture en flux d'une playlist M3U / M3U8 (#EXTINF). */
object M3uParser {
    private val ATTR = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")

    fun parse(url: String, fn: (M3uEntry) -> Unit) {
        val c = Http.open(url)
        try {
            c.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                var name: String? = null
                var group = ""
                var logo: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    val t = line.trim()
                    when {
                        t.startsWith("#EXTINF", ignoreCase = true) -> {
                            val comma = titleComma(t)
                            val header = if (comma >= 0) t.substring(0, comma) else t
                            val attrs = ATTR.findAll(header).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                            name = (if (comma >= 0) t.substring(comma + 1).trim() else "")
                                .ifBlank { attrs["tvg-name"] ?: "" }
                            group = attrs["group-title"] ?: ""
                            logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
                        }
                        t.startsWith("#EXTGRP:", ignoreCase = true) -> {
                            if (group.isEmpty()) group = t.substringAfter(':').trim()
                        }
                        t.isNotEmpty() && !t.startsWith("#") && name != null -> {
                            fn(M3uEntry(name, group, logo, t))
                            name = null; group = ""; logo = null
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    /** Première virgule hors guillemets : sépare les attributs du titre. */
    private fun titleComma(s: String): Int {
        var inQuotes = false
        for (i in s.indices) {
            when (s[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return i
            }
        }
        return -1
    }
}
