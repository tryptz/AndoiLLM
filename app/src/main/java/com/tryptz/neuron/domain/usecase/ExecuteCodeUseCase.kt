package com.tryptz.neuron.domain.usecase

import com.tryptz.neuron.code.sandbox.CodeExecutor
import com.tryptz.neuron.domain.model.CodeBlock
import com.tryptz.neuron.domain.model.CodeOutput
import com.tryptz.neuron.domain.model.InferenceSettings
import javax.inject.Inject

/**
 * Runs a code block in the sandboxed execution engine and returns the output.
 *
 * This is the chat-originated execution path. It deliberately does NOT pass
 * `confirmed = true`, so a BASH code block coming from an LLM chat response is
 * refused by [CodeExecutor.execute]. Shell execution is only possible from the
 * Code Editor screen, after an explicit per-run user confirmation dialog.
 */
class ExecuteCodeUseCase @Inject constructor(
    private val codeExecutor: CodeExecutor
) {
    suspend operator fun invoke(
        codeBlock: CodeBlock,
        settings: InferenceSettings
    ): CodeOutput = codeExecutor.execute(
        code = codeBlock.code,
        language = codeBlock.language,
        settings = settings
        // confirmed intentionally omitted (defaults to false) — bash from chat is refused.
    )
}
