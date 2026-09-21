package com.itsluminous.cleartravel.di

import com.itsluminous.cleartravel.ui.intake.OcrSharedDocProbe
import com.itsluminous.cleartravel.ui.intake.SharedDocProbe
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** App-shell seams: the share-sheet file-intake probe over the OCR pipeline. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindingsModule {
    @Binds
    abstract fun bindSharedDocProbe(impl: OcrSharedDocProbe): SharedDocProbe
}
