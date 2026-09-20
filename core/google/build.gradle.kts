plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.itsluminous.cleartravel.core.google"
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)

    // Credential Manager / Google Identity wiring lands in the Google milestone:
    // libs.androidx.credentials, libs.androidx.credentials.play.services.auth,
    // libs.google.identity.googleid, libs.play.services.auth are already catalogued.

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
