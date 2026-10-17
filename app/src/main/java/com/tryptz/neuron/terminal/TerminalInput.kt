package com.tryptz.neuron.terminal

import java.io.File
import java.io.Serializable

/** Code handed to the terminal from chat. It is typed, never executed. */
data class TerminalInput(val code: String, val python: Boolean) : Serializable

/**
 * Turns [TerminalInput] into one line to type at the prompt.
 *
 * A newline typed into a shell is Enter, and only bash enables bracketed
 * paste, so multi-line code would run line by line in Android's mksh. To keep
 * "nothing runs until the user presses Enter" true in every shell, anything
 * longer than one line (and all Python) is saved to a file and the typed line
 * is the command that runs it.
 */
object TerminalInputStager {
    fun commandFor(input: TerminalInput, snippetsDir: File, shellIsBash: Boolean): String {
        val code = input.code.trimEnd()
        if (!input.python && !code.contains('\n')) return code
        snippetsDir.mkdirs()
        val n = (snippetsDir.listFiles()?.size ?: 0) + 1
        val file = File(snippetsDir, if (input.python) "$n.py" else "$n.sh")
        file.writeText(code + "\n")
        val path = file.absolutePath
        return when {
            input.python -> "python3 $path"
            shellIsBash -> "bash $path"
            else -> "sh $path"
        }
    }
}
