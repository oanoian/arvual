package com.pocketdroid.ide

import android.content.Context
import java.io.File

/**
 * TranslateEngine — "translation" layer that turns on-device project sources
 * into deployable artifacts:
 *
 *   1. Web pages / static sites      -> copy to webroot, serve via LocalServer (localhost)
 *   2. Node apps (package.json)      -> npm install + npm start behind localhost proxy
 *   3. Python apps (app.py/main.py)  -> runtime python -m http.server / flask-style serve
 *   4. Desktop executables (.exe)    -> cannot be produced ON Android (different ABI/OS).
 *                                       We emit a ready-to-build desktop bundle
 *                                       (source + build-exe.sh / build-exe.bat) that
 *                                       cross-packages the same code with PyInstaller
 *                                       or pkg on a PC. This is the honest translation.
 */
object TranslateEngine {

    enum class ProjectKind { WEB_STATIC, NODE_APP, PYTHON_APP, DESKTOP_EXE_BUNDLE, UNKNOWN }

    data class TranslationResult(
        val kind: ProjectKind,
        val message: String,
        val webRoot: File?,          // directory to serve over localhost
        val startCommand: String?    // shell command to run in TerminalEngine before serving
    )

    /** Detect what kind of project lives in [dir] and prepare it for deployment. */
    fun translate(context: Context, dir: File): TranslationResult {
        val rt = RuntimeManager.binDir(context) // e.g. <files>/runtimes/bin
        return when (detectKind(dir)) {
            ProjectKind.WEB_STATIC ->
                TranslationResult(
                    ProjectKind.WEB_STATIC,
                    "Static site ready — serving ${dir.name} on http://localhost:${LocalServer.PORT}",
                    webRoot = dir, startCommand = null
                )

            ProjectKind.NODE_APP -> {
                // npm install via runtime PATH; app itself binds a port we proxy to.
                val start = "export PATH=\$PATH:${rt.absolutePath}; cd ${dir.absolutePath} && npm install && npm start"
                TranslationResult(
                    ProjectKind.NODE_APP,
                    "Node app: run `npm install && npm start` (terminal), then open its port in Browser panel.",
                    webRoot = null, startCommand = start
                )
            }

            ProjectKind.PYTHON_APP -> {
                val entry = findEntry(dir, "app.py", "main.py")!!.name
                val start = "export PATH=\$PATH:${rt.absolutePath}; cd ${dir.absolutePath} && python $entry"
                // If it's a plain script that writes HTML, fall back to http.server
                val serve = "export PATH=\$PATH:${rt.absolutePath}; cd ${dir.absolutePath} && python -m http.server 8000"
                TranslationResult(
                    ProjectKind.PYTHON_APP,
                    "Python app '$entry': use start command, or 'python -m http.server 8000' for static output.",
                    webRoot = dir, startCommand = "$start  # or: $serve"
                )
            }

            ProjectKind.DESKTOP_EXE_BUNDLE -> {
                emitDesktopBuildScripts(dir)
                TranslationResult(
                    ProjectKind.DESKTOP_EXE_BUNDLE,
                    "Desktop bundle prepared: build-exe.sh / build-exe.bat added. Run on a PC to produce .exe/.app binaries.",
                    webRoot = null, startCommand = null
                )
            }

            ProjectKind.UNKNOWN ->
                TranslationResult(ProjectKind.UNKNOWN, "No deployable entry point found in ${dir.name}", null, null)
        }
    }

    /** Emit cross-build scripts so the same Python/Node code becomes an .exe on a desktop OS. */
    private fun emitDesktopBuildScripts(dir: File) {
        val py = findEntry(dir, "app.py", "main.py")?.name
        val sh = buildString {
            appendLine("#!/usr/bin/env bash")
            appendLine("# Cross-build a desktop executable from this project (run on Windows/Linux/macOS).")
            if (py != null) {
                appendLine("pip install pyinstaller")
                appendLine("pyinstaller --onefile --name ${dir.name} $py")
                appendLine("echo \"Executable in dist/\"")
            } else {
                appendLine("npm install -g pkg")
                appendLine("pkg . --targets node18-win-x64,node18-linux-x64,node18-macos-x64")
                appendLine("echo \"Executables written next to package.json\"")
            }
        }
        File(dir, "build-exe.sh").writeText(sh)
        File(dir, "build-exe.bat").writeText(
            if (py != null) "@echo off\r\npip install pyinstaller\r\npyinstaller --onefile --name ${dir.name} $py\r\n"
            else "@echo off\r\nnpm install -g pkg\r\npkg . --targets node18-win-x64\r\n"
        )
    }

    private fun exists(dir: File, vararg names: String) =
        names.any { File(dir, it).isFile }

    private fun findEntry(dir: File, vararg names: String): File? =
        names.map { File(dir, it) }.firstOrNull { it.isFile }

    fun detectKind(dir: File): ProjectKind = when {
        exists(dir, "index.html") && !exists(dir, "package.json", "app.py", "main.py") -> ProjectKind.WEB_STATIC
        exists(dir, "package.json") -> ProjectKind.NODE_APP
        findEntry(dir, "app.py", "main.py") != null -> ProjectKind.PYTHON_APP
        exists(dir, "desktop.spec", "build_exe.py") -> ProjectKind.DESKTOP_EXE_BUNDLE
        else -> ProjectKind.UNKNOWN
    }
}
