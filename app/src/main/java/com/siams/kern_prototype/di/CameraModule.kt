package com.siams.kern_prototype.di

import com.siams.kern_prototype.camera.KernCamera
import com.siams.kern_prototype.camera.ToupcamController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Выбор активной реализации камеры (§13).
 *
 * Основной путь съёмки — внешняя USB-камера **Touptek** ([ToupcamController]).
 * Встроенная камера телефона ([com.siams.kern_prototype.camera.CameraController],
 * Camera2) остаётся в кодовой базе как запасной вариант: чтобы вернуться на неё,
 * достаточно заменить привязку ниже на `CameraController`.
 *
 * Привязка без скоупа — каждый экран (VM) получает свой экземпляр и освобождает
 * его в `onCleared()`, как и прежде.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CameraModule {

    @Binds
    abstract fun bindKernCamera(impl: ToupcamController): KernCamera
}
