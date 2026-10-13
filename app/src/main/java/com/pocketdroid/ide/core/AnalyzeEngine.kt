package com.pocketdroid.ide.core

import java.io.File

/**
 * SimpleAnalyzeEngine — the pragmatic "PROBLEMS" panel before full LSP.
 * Runs language-appropriate lightweight checks when a file is saved and
 * publishes Diagnostic events on the CoreBridge bus. This is deliberately NOT
 * pretending to be IntelliSense; it gives real, honest signal for the two
 * stacks we can actually run on-device (Python via bundled interpreter,
 * JS/TS/HTML/CSS heuristics) and stays silent otherwise until an LSP client
 * (pyright/tsserver over stdio) lands as the next milestone.
 */
object AnalyzeEngine {

    fun analyzeNow(file: File, text: String) {
        val diags = when (file.extension.lowercase()) {
            "py" -> pythonChecks(file, text)
            "js", "ts", "jsx", "tsx", "mjs", "cjs" -> jsChecks(text)
            "html", "htm" -> htmlChecks(text)
            else -> emptyList()
        }
        diags.forEach { CoreBridge.publish(it) }
        // Also publish a summary event so UI can show counts even if zero.
        CoreBridge.publish(CoreBridge.Event.Diagnostic(file.name, 0, 0, "info",
            "analyzed: ${diags.size} issue(s)"))
    }

    private fun d(f: File, line: Int, col: Int, sev: String, msg: String) =
        CoreBridge.Event.Diagnostic(f.name, line, col, sev, msg)

    private fun pythonChecks(file: File, text: String): List<CoreBridge.Event.Diagnostic> {
        val out = ArrayList<CoreBridge.Event.Diagnostic>()
        // Balanced brackets across the whole file (cheap indentation-agnostic check).
        var depth = 0; var badLine = 0
        text.lines().forEachIndexed { i, raw ->
            val line = stripPyComment(raw)
            for (ch in line) when (ch) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> { depth--; if (depth < 0 && badLine == 0) badLine = i + 1 }
            }
        }
        if (depth != 0) out += d(file, badLine.takeIf { it > 0 } ?: 1, 0, "error",
            if (depth > 0) "unclosed bracket (${depth} open)" else "extra closing bracket")
        // tabs mixed with leading spaces on adjacent lines
        val lines = text.split("\n")
        for (i in 1 until lines.size) {
            val prev = lines[i - 1]; val cur = lines[i]
            if (prev.startsWith("    ") && cur.startsWith("\t") ||
                prev.startsWith("\t") && cur.startsWith("    ")) {
                out += d(file, i + 1, 0, "warning", "mixed tabs/spaces indentation")
                break
            }
        }
        return out
    }

    private fun stripPyComment(line: String): String {
        var inS: Char? = null
        for ((idx, ch) in line.withIndex()) {
            if (inS == ch) inS = null
            else if (ch == '"' || ch == '\'') inS = ch
            else if (ch == '#' && inS == null) return line.substring(0, idx)
        }
        return line
    }

    private fun jsChecks(text: String): List<CoreBridge.Event.Diagnostic> {
        val out = ArrayList<CoreBridge.Event.Diagnostic>()
        var round = 0; var square = 0; var curly = 0; var firstBad = Pair<Int, String>? null
        text.lines().forEachIndexed { i, raw ->
            val line = stripJsComment(raw)
            for (ch in line) when (ch) {
                '(' -> round++; ')' -> { round--; if (round < 0 && firstBad == null) firstBad = (i + 1) to ")" }
                '[' -> square++; ']' -> { square--; if (square < 0 && firstBad == null) firstBad = (i + 1) to "]" }
                '{' -> curly++; '}' -> { curly--; if (curly < 0 && firstBad == null) firstBad = (i + 1) to "}" }
            }
        }
        firstBad?.let { (ln, c) -> out.add(CoreBridge.Event.Diagnostic("(js)", ln, 0, "error", "unexpected '$c'")) }
        if (round != 0 || square != 0 || curly != 0)
            out.add(CoreBridge.Event.Diagnostic("(js)", text.lines().size, 0, "error",
                "unbalanced delimiters ()=$round []=$square {}=$curly"))
        return out.map { it.copy(file = "") }.map { CoreBridge.Event.Diagnostic("", it.line, 0, it.severity, it.message) }
    }

    private fun stripJsComment(line: String): String {
        val t = line.trimStart()
        if (t.startsWith("//") || t.startsWith("*")) return ""
        return line.replace("//[^\"']*$".toRegex(), "")
    }

    private fun htmlChecks(text: String): List<CoreBridge.Event.Diagnostic> {
        val out = ArrayList<CoreBridge.Event.Diagnostic>()
        val voidTags = setOf("br", "img", "input", "hr", "meta", "link", "area", "base", "col", "source", "track", "wbr")
        val stack = ArrayDeque<Pair<String, Int>>()
        Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>").findAll(text).forEach { m ->
            val closing = m.groupValues[1] == "/"
            val tag = m.groupValues[2].lowercase()
            val selfClose = m.groupValues[3].trimEnd().endsWith("/")
            val line = text.substring(0, m.range.first).count { it == '\n' } + 1
            if (tag in voidTags || selfClose) return@forEach
            if (closing) {
                if (stack.isEmpty()) out.add(CoreBridge.Event.Diagnostic("(html)", line, 0, "warning", "stray </$tag>"))
                else {
                    val (top, topLine) = stack.removeLast()
                    if (top != tag) {
                        out.add(CoreBridge.Event.Diagnostic("(html)", line, 0, "warning", "</$tag> closes <$top> opened at line $topLine"))
                        stack.addLast(top to topLine) // keep going, best effort
                    }
                }
            } else stack.addLast(tag to line)
        }
        stack.toList().take(5).forEach { (tag, ln) ->
            out.add(CoreBridge.Event.Diagnostic("(html)", ln, 0, "warning", "<$tag> never closed"))
        }
        return out.map { it.copy(file = "") }.map { CoreBridge.Event.Diagnostic("", it.line, 0, it.severity, it.message) }
    }
}
