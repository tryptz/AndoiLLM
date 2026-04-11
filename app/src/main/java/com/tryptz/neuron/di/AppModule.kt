package com.tryptz.neuron.di

import android.content.Context
import androidx.work.WorkManager
import androidx.room.Room
import com.tryptz.neuron.data.local.NeuronDatabase
import com.tryptz.neuron.data.local.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NeuronDatabase =
        Room.databaseBuilder(context, NeuronDatabase::class.java, "neuron.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideConversationDao(db: NeuronDatabase): ConversationDao = db.conversationDao()
    @Provides fun provideMessageDao(db: NeuronDatabase): MessageDao = db.messageDao()
    @Provides fun provideInstalledModelDao(db: NeuronDatabase): InstalledModelDao = db.installedModelDao()
    @Provides fun provideLocalModelDao(db: NeuronDatabase): LocalModelDao = db.localModelDao()
    @Provides fun provideCodeSnippetDao(db: NeuronDatabase): CodeSnippetDao = db.codeSnippetDao()

    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient = HttpClient(OkHttp) {
        engine {
            config {
                followRedirects(true)
                retryOnConnectionFailure(true)
            }
        }
    }

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
