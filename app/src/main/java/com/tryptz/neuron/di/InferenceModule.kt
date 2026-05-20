package com.tryptz.neuron.di

import com.tryptz.neuron.code.sandbox.CodeExecutor
import com.tryptz.neuron.code.sandbox.CodeExecutorImpl
import com.tryptz.neuron.data.repository.ModelRepository
import com.tryptz.neuron.data.repository.ModelRepositoryImpl
import com.tryptz.neuron.inference.backend.InferenceEngine
import com.tryptz.neuron.inference.backend.InferenceEngineImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the inference / code-execution / model-storage interfaces to their
 * concrete implementations. Consumers inject the interfaces; the `*Impl`
 * classes carry the `@Inject constructor` and `@Singleton` scope.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class InferenceModule {

    @Binds
    @Singleton
    abstract fun bindInferenceEngine(impl: InferenceEngineImpl): InferenceEngine

    @Binds
    @Singleton
    abstract fun bindCodeExecutor(impl: CodeExecutorImpl): CodeExecutor

    @Binds
    @Singleton
    abstract fun bindModelRepository(impl: ModelRepositoryImpl): ModelRepository
}
