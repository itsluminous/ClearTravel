plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.itsluminous.cleartravel.core.testing"
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
    api(project(":core:model"))

    // Test infra is exposed as MAIN source here so other modules can
    // testImplementation(project(":core:testing")). junit/truth/coroutines-test are
    // `api` so consumers get them transitively.
    api(libs.junit)
    api(libs.truth)
    api(libs.kotlinx.coroutines.test)
    implementation(libs.room.runtime)
    implementation(libs.kotlinx.coroutines.core)
}
