package io.ntivo.parse

import io.ntivo.ClassDecl
import io.ntivo.FunctionChunk
import io.ntivo.findClassDecls
import io.ntivo.findFunctions
import io.ntivo.parseKotlin

/** Languages Ntivo can split into pieces. */
enum class Language(val displayName: String, val extensions: Set<String>) {
    KOTLIN("Kotlin", setOf("kt")),
    SWIFT("Swift", setOf("swift"));

    companion object {
        /** The language for a file name or path by its extension, or null when unsupported. */
        fun forFile(name: String): Language? {
            val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            return entries.firstOrNull { ext in it.extensions }
        }
    }
}

/** Functions and classes found in one source file. */
data class ParsedFile(val functions: List<FunctionChunk>, val classes: List<ClassDecl>)

/** Parses the source of one file in a single [language]. */
interface LanguageParser {
    val language: Language
    fun parse(source: String): ParsedFile
}

/** Kotlin, via the existing Tree-sitter functions in `CodeParser.kt`. */
object KotlinParser : LanguageParser {
    override val language = Language.KOTLIN

    override fun parse(source: String): ParsedFile {
        val root = parseKotlin(source).rootNode
        val functions = mutableListOf<FunctionChunk>().also { findFunctions(root, source, it) }
        val classes = mutableListOf<ClassDecl>().also { findClassDecls(root, source, it) }
        return ParsedFile(functions, classes)
    }
}

/** The parser for [language]. */
fun parserFor(language: Language): LanguageParser = when (language) {
    Language.KOTLIN -> KotlinParser
    Language.SWIFT -> SwiftParser
}
