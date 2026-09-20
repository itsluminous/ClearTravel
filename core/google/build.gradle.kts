import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Same convention as :app (hard rule 6): the Google OAuth web client id comes from
// local.properties (git-ignored) with a safe empty default. An empty id puts the whole
// Google integration into a "not configured" state instead of crashing — see
// docs/google-setup.md for how to obtain a real value.
val localProps =
    Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use(::load)
    }

fun quotedProp(name: String): String = "\"${localProps.getProperty(name)?.trim().orEmpty()}\""

android {
    namespace = "com.itsluminous.cleartravel.core.google"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", quotedProp("GOOGLE_WEB_CLIENT_ID"))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
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
    api(project(":core:model"))
    api(project(":core:data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.google.identity.googleid)
    implementation(libs.play.services.auth)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
