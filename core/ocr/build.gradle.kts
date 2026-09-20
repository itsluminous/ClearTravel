plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
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
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    // ML Kit wiring lands with the Trains/Flights import milestones:
    // libs.mlkit.text.recognition and libs.mlkit.barcode.scanning are catalogued.

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
