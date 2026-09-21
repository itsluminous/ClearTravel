import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Maps/Google settings come from local.properties (git-ignored) with safe empty
// defaults, so a fresh checkout and CI both build without any secrets.
val localProps =
    Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use(::load)
    }

fun localProp(name: String): String = localProps.getProperty(name)?.trim().orEmpty()

fun quotedProp(name: String): String = "\"${localProp(name)}\""

// Release signing is driven entirely by environment variables (same convention as CI
// secrets): unset variables yield an unsigned release APK instead of a failing build.
val signingKeystoreFile: String? = System.getenv("SIGNING_KEYSTORE_FILE")
val signingKeystorePassword: String? = System.getenv("SIGNING_KEYSTORE_PASSWORD")
val signingKeyAlias: String? = System.getenv("SIGNING_KEY_ALIAS")
val signingKeyPassword: String? = System.getenv("SIGNING_KEY_PASSWORD")
val hasReleaseSigning =
    !signingKeystoreFile.isNullOrBlank() &&
        !signingKeystorePassword.isNullOrBlank() &&
        !signingKeyAlias.isNullOrBlank() &&
        !signingKeyPassword.isNullOrBlank()

android {
    namespace = "com.itsluminous.cleartravel"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.itsluminous.cleartravel"
        minSdk = 26
        targetSdk = 36
        versionCode = (project.findProperty("appVersionCode") as? String)?.toInt() ?: 1
        versionName = (project.findProperty("appVersionName") as? String) ?: "0.1.0"

        testInstrumentationRunner = "com.itsluminous.cleartravel.HiltTestRunner"

        // Maps SDK key read by the manifest <meta-data>; empty is a safe default
        // (map tiles render blank until a key is supplied in local.properties).
        manifestPlaceholders["MAPS_API_KEY"] = localProp("MAPS_API_KEY")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", quotedProp("GOOGLE_WEB_CLIENT_ID"))
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(signingKeystoreFile!!)
                storePassword = signingKeystorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
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
        buildConfig = true
    }

    lint {
        lintConfig = rootProject.file("lint.xml")
        abortOnError = true
    }

    testOptions {
        animationsDisabled = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Human-friendly artifact names: ClearTravel.apk / ClearTravel-debug.apk.
androidComponents {
    onVariants { variant ->
        val suffix = if (variant.buildType == "release") "" else "-${variant.buildType}"
        variant.outputs.forEach { output ->
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)
                ?.outputFileName
                ?.set("ClearTravel$suffix.apk")
        }
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:data"))
    implementation(project(":core:notifications"))
    implementation(project(":core:google"))
    // Share-sheet file intake auto-detect runs the public OCR prefill API.
    implementation(project(":core:ocr"))
    implementation(project(":feature:trains"))
    implementation(project(":feature:flights"))
    implementation(project(":feature:itinerary"))
    implementation(project(":feature:checklist"))
    implementation(project(":feature:menu"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
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

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Provides the XML Theme.Material3.* parent used in themes.xml.
    implementation(libs.google.material)

    testImplementation(project(":core:testing"))

    // Instrumented suite (one happy-path e2e per feature, from milestone 2 on).
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // Espresso.pressBack() drives the real system-back path (window key dispatch).
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.truth)
    // Hilt in tests: HiltTestApplication + @TestInstallIn module replacement
    // (TestDatabaseModule swaps the on-disk Room DB for an in-memory one).
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}
