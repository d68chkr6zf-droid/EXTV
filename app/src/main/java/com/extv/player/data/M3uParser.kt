package com.extv.player.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Lecture des listes M3U / M3U8 étendues (#EXTINF avec tvg-id, tvg-logo, group-title, catchup…). */
object M3uParser {
    data class Entry(val name: String, val url: String, val attrs: Map<String, String>)
    data class Playlist(val header: Map<String, String>, val entries: List<Entry>)

    private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(reader: BufferedReader): Playlist {
        var header: Map<String, String> = emptyMap()
        val out = ArrayList<Entry>()
        var info: String? = null
        var group: String? = null
        for (raw in reader.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> header = attributes(line)
                line.startsWith("#EXTINF", ignoreCase = true) -> info = line
                line.startsWith("#EXTGRP:", ignoreCase = true) -> group = line.substringAfter(':').trim()
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    val currentInfo = info
                    val attrs = (currentInfo?.let { attributes(it) } ?: emptyMap()).toMutableMap()
                    val currentGroup = group
                    if (currentGroup != null && attrs["group-title"].isNullOrBlank()) attrs["group-title"] = currentGroup
                    val name = currentInfo?.let { title(it) }?.ifBlank { null }
                        ?: attrs["tvg-name"]
                        ?: line.substringAfterLast('/')
                    out += Entry(name, line, attrs)
                    info = null
                    group = null
                }
            }
        }
        return Playlist(header, out)
    }

    private fun attributes(line: String): Map<String, String> =
        attrRegex.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    /** Le titre suit la première virgule située hors guillemets. */
    private fun title(info: String): String {
        var inQuotes = false
        for (i in info.indices) {
            val c = info[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) return info.substring(i + 1).trim()
        }
        return ""
    }
}

/** Lecture en flux d'un guide XMLTV ; ne garde que les programmes de la fenêtre demandée. */
object XmltvParser {
    fun parse(input: InputStream, keepFrom: Long, keepTo: Long): Map<String, List<EpgProgram>> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        val map = HashMap<String, MutableList<EpgProgram>>()
        var channel: String? = null
        var start = 0L
        var stop = 0L
        val title = StringBuilder()
        val desc = StringBuilder()
        var tag: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "programme" -> {
                        channel = parser.getAttributeValue(null, "channel")
                        start = parseTime(parser.getAttributeValue(null, "start"))
                        stop = parseTime(parser.getAttributeValue(null, "stop"))
                        title.clear()
                        desc.clear()
                    }
                    "title", "desc" -> tag = parser.name
                }
                XmlPullParser.TEXT -> when (tag) {
                    "title" -> title.append(parser.text)
                    "desc" -> desc.append(parser.text)
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "title", "desc" -> tag = null
                    "programme" -> {
                        val ch = channel
                        if (ch != null && stop >= keepFrom && start <= keepTo) {
                            map.getOrPut(ch) { mutableListOf() } +=
                                EpgProgram(title.toString().trim(), desc.toString().trim(), start, stop)
                        }
                        channel = null
                    }
                }
            }
            event = parser.next()
        }
        return map.mapValues { (_, v) -> v.sortedBy { it.start } }
    }

    fun parseTime(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        return try {
            val t = s.trim()
            if (t.length > 14 && t.contains(' ')) {
                SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).parse(t)?.time ?: 0
            } else {
                SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(t.take(14))?.time ?: 0
            }
        } catch (e: Exception) {
            0
        }
    }
}
