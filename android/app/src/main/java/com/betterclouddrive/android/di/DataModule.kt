package com.betterclouddrive.android.di

import android.content.Context
import com.betterclouddrive.android.data.local.TokenManager
import com.betterclouddrive.android.data.remote.ApiService
import com.betterclouddrive.android.data.repository.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideTokenManager(@ApplicationContext context: Context): TokenManager {
        return TokenManager(context)
    }

    @Provides
    @Singleton
    fun provideAuthRepository(apiService: ApiService, tokenManager: TokenManager): AuthRepository {
        return AuthRepository(apiService, tokenManager)
    }

    @Provides
    @Singleton
    fun provideFileRepository(apiService: ApiService): FileRepository {
        return FileRepository(apiService)
    }

    @Provides
    @Singleton
    fun provideUploadRepository(
        apiService: ApiService,
        @ApplicationContext context: Context,
    ): UploadRepository {
        return UploadRepository(apiService, context)
    }

    @Provides
    @Singleton
    fun provideTransferRepository(
        apiService: ApiService,
        @ApplicationContext context: Context,
    ): TransferRepository {
        return TransferRepository(apiService, context)
    }

    @Provides
    @Singleton
    fun provideShareRepository(apiService: ApiService): ShareRepository {
        return ShareRepository(apiService)
    }

    @Provides
    @Singleton
    fun provideFavoriteRepository(apiService: ApiService): FavoriteRepository {
        return FavoriteRepository(apiService)
    }

    @Provides
    @Singleton
    fun provideTagRepository(apiService: ApiService): TagRepository {
        return TagRepository(apiService)
    }

    @Provides
    @Singleton
    fun provideRecycleBinRepository(apiService: ApiService): RecycleBinRepository {
        return RecycleBinRepository(apiService)
    }
}
