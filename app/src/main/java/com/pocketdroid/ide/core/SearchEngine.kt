package com.pocketdroid.ide.core

import java.io.File

/**
 * SearchEngine — project-wide find/replace (VS Code's Ctrl+Shift+F equivalent).
 * Pure JVM, runs on background threads via callers. Skips binary-ish files and
 * huge ones; caps result count so the UI never chokes.
 */
object SearchEngine {

    data class Match(val file: File, val line: Int, val text: String)
    data class ReplaceReport(val files: Int, val occurrences: Int)

    private val skipExt = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "ico", "ttf", "otf", "woff", "woff2",
        "jar", "apk", "zip", "gz", "xz", "bz2", "so", "dex", "class", "mp3", "mp4",
        "wav", "ogg", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "pyc",
    )
    private val skipDirs = setOf(".git", "node_modules", "build", ".gradle", "__pycache__", ".idea")

    fun search(root: File, query: String, regex: Boolean, caseSensitive: Boolean, max: Int = 500): List<Match> {
        if (query.isEmpty()) return emptyList()
        val pattern = if (regex) runCatching { Regex(if (caseSensitive) query else "(?i)$query") }
            .getOrNull() ?: return emptyList()
        else null
        val needle = if (caseSensitive) query else query.lowercase()
        val out = ArrayList<Match>()
        walk(root) { f ->
            if (out.size >= max) return@walk
            if (!isTextish(f)) return@walk
            runCatching {
                f.forEachLineIndexed { idx, line ->
                    if (out.size >= max) return@forEachLineIndexed
                    val hit = if (pattern != null) pattern.containsMatchIn(line)
                        else (if (caseSensitive) line.contains(needle) else line.lowercase().contains(needle))
                    if (hit) out += Match(f, idx + 1, line.trim().take(160))
                }
            }
        }
        return out
    }

    fun replaceAll(root: File, find: String, replace: String, regex: Boolean, caseSensitive: Boolean): ReplaceReport {
        var files = 0; var occ = 0
        val pattern = if (regex) runCatching { Regex(find) }.getOrNull() ?: return ReplaceReport(0, 0) else null
        walk(root) { f ->
            if (!isTextish(f)) return@walk
            runCatching {
                val old = f.readText()
                val (new, n) = if (pattern != null) {
                    val m = pattern.findAll(old).count()
                    pattern.replace(old) { replace } to m
                } else {
                    val hay = if (caseSensitive) old else old.lowercase()
                    val needle = if (caseSensitive) find else find.lowercase()
                    var i = 0; var c = 0
                    val sb = StringBuilder()
                    while (true) {
                        val j = hay.indexOf(needle, i)
                        if (j < 0) { sb.append(old.substring(i)); break }
                        sb.append(old.substring(i, j)).append(replace); i = j + needle.length; c++
                    }
                    sb.toString() to c
                }
                if (n > 0 && new != old) { f.writeText(new); files++; occ += n }
            }
        }
        return ReplaceReport(files, occ)
    }

    private inline fun walk(dir: File, crossinline visit: (File) -> Unit) {
        val kids = dir.listFiles() ?: return
        for (k in kids) when {
            k.isDirectory -> if (k.name !in skipDirs) walk(k, visit)
            else -> visit(k)
        }
    }

    private fun isTextish(f: File) =
        f.length() in 1..2_000_000 &&
            f.extension.lowercase() !in skipExt

    private inline fun File.forEachLineIndexed(action: (Int, String) -> Unit) {
        bufferedReader().use { r ->
            var i = 0
            while (true) {
                val line = r.readLine() ?: break
                action(i, line); i++
            }
        }
    }
}
