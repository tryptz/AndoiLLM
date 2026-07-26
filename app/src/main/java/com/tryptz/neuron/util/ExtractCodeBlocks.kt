package com.tryptz.neuron.util

import com.tryptz.neuron.domain.model.CodeBlock
import com.tryptz.neuron.domain.model.CodeLanguage

/**
 * Extract fenced code blocks from markdown-formatted text.
 * Returns pairs of (language, code).
 */
fun String.extractCodeBlocks(): List<Pair<String, String>> {
    // Accept any info string after the fence (```c++, ```python title=x) —
    // requiring \w* made non-word tags fail to OPEN a block, which then let
    // that block's closing fence pair with the next block's opener and
    // swallow the prose in between. Also tolerate CRLF line endings.
    val pattern = Regex("```([^\\r\\n]*)\\r?\\n([\\s\\S]*?)```")
    return pattern.findAll(this).map { match ->
        val lang = match.groupValues[1].trim().substringBefore(' ').ifBlank { "text" }
        val code = match.groupValues[2].trimEnd()
        lang to code
    }.toList()
}

/** Maps a markdown fence language tag to a runnable [CodeLanguage], or null if unrecognized. */
fun codeLanguageFromFenceTag(tag: String): CodeLanguage? = when (tag.lowercase()) {
    "js", "javascript", "jsx", "mjs", "node" -> CodeLanguage.JAVASCRIPT
    "py", "python", "python3" -> CodeLanguage.PYTHON
    "sh", "bash", "shell", "zsh" -> CodeLanguage.BASH
    "html", "htm" -> CodeLanguage.HTML
    else -> null
}

/**
 * Parses [content]'s fenced code blocks into [CodeBlock]s.
 *
 * The fence tag is authoritative for the language (the model said what it
 * wrote); [detect] is only a fallback for blank/unknown tags. Ids are
 * DETERMINISTIC ("<messageId>-<index>") so a block keeps its identity across
 * Room re-emissions and run output can be matched back to it.
 */
fun parseCodeBlocks(
    messageId: String,
    content: String,
    detect: (String) -> CodeLanguage
): List<CodeBlock> =
    content.extractCodeBlocks().mapIndexed { index, (lang, code) ->
        CodeBlock(
            id = "$messageId-$index",
            language = codeLanguageFromFenceTag(lang) ?: detect(code),
            code = code
        )
    }
