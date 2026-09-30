package com.pocketdroid.ide

import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.DefaultGrammarDefinition
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IThemeSource
import java.nio.charset.StandardCharsets

/**
 * Minimal TextMate bootstrap: bundled grammars (Kotlin, Java) and a dark theme.
 * Grammar/theme files live in assets/grammars & assets/themes and are resolved
 * through an AssetManager-backed FileResolver so tm4e never touches the network.
 */
object TextMateBootstrap {

    @Volatile
    private var initialized = false

    val languageScopeMap = mapOf(
        "kt" to "source.kotlin",
        "kts" to "source.kotlin",
        "java" to "source.java",
        "json" to "source.json",
    )

    fun init(context: android.content.Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return

            // Resolve "kotlin.json" style paths out of assets/grammars/.
            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(context.assets))

            GrammarRegistry.getInstance().loadGrammars(
                listOf(
                    grammarDef("kotlin.json", "source.kotlin"),
                    grammarDef("java.json", "source.java"),
                )
            )

            val themeModel = ThemeModel(
                IThemeSource.fromInputStream(
                    AssetsBundle.openAsset("themes/dark.json"),
                    "dark.json",
                    StandardCharsets.UTF_8,
                ),
                "dark",
            )
            ThemeRegistry.getInstance().loadTheme(themeModel, true)
            ThemeRegistry.getInstance().setTheme("dark")

            initialized = true
        }
    }

    private fun grammarDef(fileName: String, scopeName: String): DefaultGrammarDefinition {
        val source = IGrammarSource.fromInputStream(
            AssetsBundle.openAsset("grammars/$fileName"),
            fileName,
            StandardCharsets.UTF_8,
        )
        return DefaultGrammarDefinition.withGrammarSource(source, scopeName, null)
    }
}
