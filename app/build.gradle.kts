import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val sherpaAar = file("libs/sherpa-onnx.aar")
if (!sherpaAar.exists()) {
    logger.warn("app/libs/sherpa-onnx.aar is missing: run scripts/fetch-sherpa-onnx.sh before building the app.")
}

// Stable signing (issue #15). Android only installs an update when it is signed with the same key
// as the installed app, so CI signs every build (debug and release) with one long-lived key.
// Each value is read from a Gradle property or, failing that, an environment variable of the same
// name. Use an absolute path for the keystore. See CONTRIBUTING.md, "Signing".
val signingKeys = listOf(
    "TRAVELGUIDE_KEYSTORE_FILE",
    "TRAVELGUIDE_KEYSTORE_PASSWORD",
    "TRAVELGUIDE_KEY_ALIAS",
    "TRAVELGUIDE_KEY_PASSWORD",
)
val signing: Map<String, String> = signingKeys.mapNotNull { key ->
    (providers.gradleProperty(key).orNull ?: providers.environmentVariable(key).orNull)
        ?.takeIf { it.isNotBlank() }
        ?.let { key to it }
}.toMap()
val hasStableSigning = signing.size == signingKeys.size
if (!hasStableSigning) {
    logger.warn(
        "No stable signing key (TRAVELGUIDE_KEYSTORE_FILE etc.): debug builds use the default debug key " +
            "and release builds are unsigned. Builds signed with different keys don't install over each other.",
    )
}

android {
    namespace = "io.github.atrejoe.travelguide"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.atrejoe.travelguide"
        minSdk = 26
        targetSdk = 35
        // CI passes a time-based versionCode (minutes since 2026-01-01 UTC) so it only ever goes up:
        // Android refuses to install a lower versionCode over a higher one.
        versionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        // The base version lives in gradle.properties (appVersion); CI adds a -prN.run / -dev.run suffix.
        versionName = (findProperty("versionName") as String?) ?: (findProperty("appVersion") as String?) ?: "0.0.0"

        ndk {
            // Keeps the APK small: virtually all current phones are arm64; x86_64 is for emulators.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (hasStableSigning) {
            create("stable") {
                storeFile = file(signing.getValue("TRAVELGUIDE_KEYSTORE_FILE"))
                storePassword = signing.getValue("TRAVELGUIDE_KEYSTORE_PASSWORD")
                keyAlias = signing.getValue("TRAVELGUIDE_KEY_ALIAS")
                keyPassword = signing.getValue("TRAVELGUIDE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        if (hasStableSigning) {
            getByName("debug") {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
        release {
            if (hasStableSigning) {
                signingConfig = signingConfigs.getByName("stable")
            }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

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

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
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
