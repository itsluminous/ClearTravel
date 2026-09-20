plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.itsluminous.cleartravel.core.ocr"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        lintConfig = rootProject.file("lint.xml")
        abortOnError = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    // Bridges ML Kit's Task API into suspend functions (Task.await()).
    implementation(libs.kotlinx.coroutines.play.services)
    // Extraction results are @Serializable so fixture tests can compare against expected JSON.
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // On-device only: text OCR + BCBP barcode decoding. Files never leave the device.
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.barcode.scanning)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    // Thin Robolectric coverage only where Bitmap forces it (preprocessor); everything
    // else in this module is pure Kotlin tested as plain JUnit.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
}
