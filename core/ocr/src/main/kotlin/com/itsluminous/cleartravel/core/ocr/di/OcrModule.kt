package com.itsluminous.cleartravel.core.ocr.di

import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the on-device ML Kit clients. Everything else in this module is
 * constructor-injected; only the ML Kit clients need option configuration here.
 * Boarding-pass BCBP barcodes ship as PDF417 (print-at-home/airport), Aztec or QR
 * (mobile passes) — scanning is restricted to those formats for speed and accuracy.
 */
@Module
@InstallIn(SingletonComponent::class)
object OcrModule {
    @Provides
    @Singleton
    fun provideTextRecognizer(): TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @Provides
    @Singleton
    fun provideBarcodeScanner(): BarcodeScanner =
        BarcodeScanning.getClient(
            BarcodeScannerOptions
                .Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_PDF417,
                    Barcode.FORMAT_AZTEC,
                    Barcode.FORMAT_QR_CODE,
                ).build(),
        )
}
