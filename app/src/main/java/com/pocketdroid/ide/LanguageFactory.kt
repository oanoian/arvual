package com.pocketdroid.ide

import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * Maps file extensions to TextMate scopes and builds sora-editor languages.
 */
object LanguageFactory {

    fun scopeFor(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt", "kts" -> "source.kotlin"
            "java" -> "source.java"
            "json" -> "source.json"
            "xml" -> "text.xml"
            else -> null
        }
    }

    fun applyTo(editor: CodeEditor, fileName: String) {
        val scope = scopeFor(fileName)
        if (scope == null) {
            editor.setEditorLanguage(io.github.rosemoe.sora.langs.plaintext.PlainTextLanguage())
            return
        }
        try {
            val lang = TextMateLanguage.create(
                scope,
                true,
                GrammarRegistry.instance.getThemeModel(ThemeRegistry.instance.themeName),
            )
            editor.setEditorLanguage(lang)
        } catch (_: Exception) {
            editor.setEditorLanguage(io.github.rosemoe.sora.langs.plaintext.PlainTextLanguage())
        }
    }
}
