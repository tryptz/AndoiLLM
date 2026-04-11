package com.tryptz.neuron.domain.usecase

import com.tryptz.neuron.code.sandbox.CodeExecutor
import com.tryptz.neuron.domain.model.CodeBlock
import com.tryptz.neuron.domain.model.CodeOutput
import com.tryptz.neuron.domain.model.InferenceSettings
import javax.inject.Inject

/**
 * Runs a code block in the sandboxed execution engine
 * and returns the output.
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
    )
}
