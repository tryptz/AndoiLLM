package com.tryptz.neuron.util

/**
 * Extract fenced code blocks from markdown-formatted text.
 * Returns pairs of (language, code).
 */
fun String.extractCodeBlocks(): List<Pair<String, String>> {
    val pattern = Regex("```(\\w*)\\n([\\s\\S]*?)```")
    return pattern.findAll(this).map { match ->
        val lang = match.groupValues[1].ifBlank { "text" }
        val code = match.groupValues[2].trimEnd()
        lang to code
    }.toList()
}
