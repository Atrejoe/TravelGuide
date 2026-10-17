// AGP 9 compiles Kotlin itself (built-in Kotlin), so the kotlin-android plugin is not applied.
// The Compose compiler plugin also brings the Kotlin Gradle plugin at the catalog's `kotlin`
// version, which takes precedence over the older one AGP depends on.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val sherpaAar = file("libs/sherpa-onnx.aar")
if (!sherpaAar.exists()) {
    logger.warn("app/libs/sherpa-onnx.aar is missing: run scripts/fetch-sherpa-onnx.sh before building the app.")
}

android {
    namespace = "io.github.atrejoe.travelguide"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.atrejoe.travelguide"
        minSdk = 26
        targetSdk = 35
        versionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "0.1.0"

        ndk {
            // Keeps the APK small: virtually all current phones are arm64; x86_64 is for emulators.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Built-in Kotlin takes its JVM target from targetCompatibility.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        // CI runs lintDebug on every app change; no need to repeat it for release builds.
        checkReleaseBuilds = false
        // Print findings in the build log so they are visible without downloading the report.
        textReport = true
        textOutput = File("stdout")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(files("libs/sherpa-onnx.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.media)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.osmdroid)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)
}
