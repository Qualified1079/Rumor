package com.rumor.mesh.di

import android.content.Context
import com.rumor.mesh.data.RumorDatabase
import com.rumor.mesh.data.BreadcrumbDao
import com.rumor.mesh.data.ContactDao
import com.rumor.mesh.data.MessageDao
import com.rumor.mesh.data.RouteDao
import com.rumor.mesh.service.MeshController
import com.rumor.mesh.service.MeshService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): RumorDatabase =
        RumorDatabase.create(ctx)

    @Provides @Singleton
    fun provideMessageDao(db: RumorDatabase): MessageDao = db.messageDao()

    @Provides @Singleton
    fun provideContactDao(db: RumorDatabase): ContactDao = db.contactDao()

    @Provides @Singleton
    fun provideBreadcrumbDao(db: RumorDatabase): BreadcrumbDao = db.breadcrumbDao()

    @Provides @Singleton
    fun provideRouteDao(db: RumorDatabase): RouteDao = db.routeDao()
}
