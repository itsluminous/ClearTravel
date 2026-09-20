// Top-level build file: plugin versions come from gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ktlint)
}

// Apply ktlint to every module so `./gradlew ktlintCheck` covers the whole project.
allprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.3.1")
        android.set(true)
        filter {
            exclude { it.file.path.contains("${layout.buildDirectory.get()}") }
            exclude("**/build/**")
        }
    }
}

// Library modules without androidTest sources must not produce a test APK: the empty
// APK self-instruments with the LEGACY android.test.InstrumentationTestRunner (no
// runner is configured) which ANRs on modern API levels and fails the root
// `connectedDebugAndroidTest`. The app module hosts the e2e suite.
subprojects {
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.api.variant.LibraryAndroidComponentsExtension> {
            beforeVariants { variant ->
                variant.androidTest.enable =
                    variant.androidTest.enable && projectDir.resolve("src/androidTest").exists()
            }
        }
    }
}
