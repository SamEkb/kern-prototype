package com.siams.kern_prototype.di

import android.content.Context
import androidx.room.Room
import com.siams.kern_prototype.data.db.KernDatabase
import com.siams.kern_prototype.data.db.dao.DrillingDao
import com.siams.kern_prototype.data.db.dao.LayerDao
import com.siams.kern_prototype.data.db.dao.TableDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KernDatabase =
        Room.databaseBuilder(context, KernDatabase::class.java, KernDatabase.NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides fun provideDrillingDao(db: KernDatabase): DrillingDao = db.drillingDao()
    @Provides fun provideTableDao(db: KernDatabase): TableDao = db.tableDao()
    @Provides fun provideLayerDao(db: KernDatabase): LayerDao = db.layerDao()
}
