plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.itsluminous.cleartravel.feature.itinerary"
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

    buildFeatures {
        compose = true
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
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))

    implementation(libs.androidx.core.ktx)
    // Place search suggestions (OSM Nominatim) — free, key-less, debounced.
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Trip map view + location picker (empty-key safe; see MapUnavailableReason).
    // maps-compose is PINNED to 6.7.0 (ADR-012 + 7d3d356): 6.12.x is compiled against
    // Compose BOM 2025.09 / Compose 1.9 and crashes at runtime on our BOM 2025.06
    // (NoSuchMethodError: rememberSaveable); bump it ONLY together with the Compose BOM.
    // Exclusions, re-verified at 6.7.0 (cleanup pass 2026-09-22): 6.7.0 declares
    // androidx.core:core-ktx 1.16.0, which would otherwise override the catalog's 1.15.0
    // for EVERY consumer, so the two core exclusions stay. Its own compose-bom import
    // (2025.04.00) is older than ours and loses resolution anyway, so that exclusion
    // was dropped as dead.
    implementation(libs.maps.compose) {
        exclude(group = "androidx.core", module = "core")
        exclude(group = "androidx.core", module = "core-ktx")
    }
    implementation(libs.play.services.maps)

    testImplementation(project(":core:testing"))
    testImplementation(libs.turbine)
}
