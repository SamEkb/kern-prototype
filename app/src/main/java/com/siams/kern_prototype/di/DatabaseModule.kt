package com.siams.kern_prototype.di

import com.siams.kern_prototype.data.db.DatabaseGate
import com.siams.kern_prototype.data.db.KernDatabase
import com.siams.kern_prototype.data.db.dao.DrillingDao
import com.siams.kern_prototype.data.db.dao.LayerDao
import com.siams.kern_prototype.data.db.dao.TableDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * БД — только через [DatabaseGate]: недоступная БД не выдаётся (исключение), пустая или
     * подменная не создаётся. Точки входа (экран, старт, воркер) проверяют шлюз заранее.
     */
    @Provides
    @Singleton
    fun provideDatabase(gate: DatabaseGate): KernDatabase = gate.requireDatabase()

    @Provides fun provideDrillingDao(db: KernDatabase): DrillingDao = db.drillingDao()
    @Provides fun provideTableDao(db: KernDatabase): TableDao = db.tableDao()
    @Provides fun provideLayerDao(db: KernDatabase): LayerDao = db.layerDao()
}
