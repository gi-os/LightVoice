import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * The key shake-to-report posts issues with. Never in the repository: `local.properties` is
 * ignored by git, and CI hands it in from a repository secret. An empty string is a working
 * build — reports queue on the phone and go out from a later one that has the key.
 */
val reportToken: String = run {
    val local = rootProject.file("local.properties")
    val fromFile = if (local.exists()) {
        Properties().apply { local.inputStream().use { load(it) } }.getProperty("reportToken")
    } else {
        null
    }
    fromFile ?: System.getenv("REPORT_TOKEN") ?: ""
}

android {
    namespace = "com.gios.lightvoice"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.gios.lightvoice"
        minSdk = 29
        targetSdk = 35
        // CI overwrites both from the workflow run number; see .github/workflows/build.yml
        versionCode = 1
        versionName = "1.2.0"

        buildConfigField("String", "REPORT_TOKEN", "\"$reportToken\"")
        buildConfigField("String", "REPORT_REPO", "\"gi-os/light-reports\"")

        // The LPIII is arm64 only; shipping four ABIs tripled the APK for nothing.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../keystore/lightvoice.jks")
            storePassword = "lightvoice"
            keyAlias = "lightvoice"
            keyPassword = "lightvoice"
        }
    }

    buildTypes {
        release {
            // Shrunk and obfuscated, with R8 in full mode (see gradle.properties). The keep
            // rules in proguard-rules.pro are the exhaustive list of things nothing in this
            // app calls by name — the manifest components above all.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Same committed key as debug, so either APK upgrades over the other.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // buildConfig carries REPORT_TOKEN into the app; see the reportToken block above.
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // The wheel, shake-to-report and the LightSync provider, shared with the other Light apps.
    implementation("com.gios:light-common:1.4.0")

    // What makes the baseline profile inside that AAR actually get applied. Below API 31
    // nothing on the phone reads a profile on its own, and the LPIII is slow enough at cold
    // start that the difference is visible.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Networking: Groq (speech), Anthropic (intent), BlueBubbles (iMessage)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // QR scanning (API key entry, same flow as LightTip/LightPass)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // The shake gesture's arithmetic is tested in light-common now, where it lives.
    testImplementation("junit:junit:4.13.2")
}
