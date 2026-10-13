package com.pocketdroid.ide.core

import android.content.Context
import java.io.File

/**
 * WorkspaceStore — the missing persistence layer. Remembers last project,
 * open tabs, cursor positions and per-file scroll so reopening the IDE feels
 * like VS Code restoring a workspace instead of starting blank.
 */
object WorkspaceStore {

    data class TabState(val path: String, val cursor: Int = 0, val scroll: Float = 0f)
    data class Workspace(
        val projectName: String? = null,
        val tabs: List<TabState> = emptyList(),
        val activeTab: String? = null,
        val terminalCwd: String? = null,
    )

    private fun storeFile(context: Context) = File(context.filesDir, "workspace.json")

    // Minimal hand-rolled JSON (no serialization dependency). Tabs are stored
    // as one "path|cursor|scroll" string per entry to keep parsing trivial.
    fun save(context: Context, ws: Workspace) {
        val sb = StringBuilder("{")
        sb.append("\"project\":").append(q(ws.projectName)).append(",")
        sb.append("\"active\":").append(q(ws.activeTab)).append(",")
        sb.append("\"cwd\":").append(q(ws.terminalCwd)).append(",")
        sb.append("\"tabs\":[")
        ws.tabs.joinTo(sb, ",") { q("${it.path}|${it.cursor}|${it.scroll}") }
        sb.append("}")
        runCatching { storeFile(context).writeText(sb.toString()) }
    }

    fun load(context: Context): Workspace {
        val f = storeFile(context)
        if (!f.exists()) return Workspace()
        return runCatching {
            val txt = f.readText()
            Workspace(
                projectName = strField(txt, "project"),
                activeTab = strField(txt, "active"),
                terminalCwd = strField(txt, "cwd"),
                tabs = arrField(txt, "tabs").mapNotNull { raw ->
                    val parts = raw.split("|")
                    if (parts.isEmpty() || parts[0].isBlank()) null
                    else TabState(
                        parts[0],
                        parts.getOrNull(1)?.toIntOrNull() ?: 0,
                        parts.getOrNull(2)?.toFloatOrNull() ?: 0f,
                    )
                },
            )
        }.getOrDefault(Workspace())
    }

    private fun q(s: String?): String =
        if (s == null) "null" else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun strField(json: String, key: String): String? {
        val m = Regex("\"$key\":\"([^\"]*)\"").find(json) ?: run {
            if (Regex("\"$key\":null").containsMatchIn(json)) return null; return null
        }
        return m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private fun arrField(json: String, key: String): List<String> {
        val start = json.indexOf("\"$key\":[")
        if (start < 0) return emptyList()
        val from = start + key.length + 3
        val end = json.indexOf(']', from)
        if (end < 0) return emptyList()
        return Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(json.substring(from, end))
            .map { it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\") }
            .toList()
    }
}
