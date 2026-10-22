import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing, from the git-ignored signing.properties (storeFile, storePassword, keyAlias,
// keyPassword). Without it, release builds fall back to the debug key.
val signingProps = Properties().apply {
    rootProject.file("signing.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

// The app version; versionCode must go up by one with every release.
val appVersionName = "0.1.2"
val appVersionCode = 3

base {
    archivesName = "Foldman-$appVersionName"
}

android {
    namespace = "dev.foldman"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.foldman"
        minSdk = 30
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        // Every current foldable is 64-bit ARM; leaving out the rest halves the APK.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (signingProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":koda"))
    coreLibraryDesugaring(kodaLibs.desugar.jdk.libs.nio)

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.window:window:1.5.1")
    implementation("androidx.palette:palette-ktx:1.0.0")

    // Home-screen widget (Glance) and ListenableFuture await() for its media-controller actions.
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.10.1")

    // Local binary dependencies, if any.
    implementation(fileTree("libs") { include("*.aar", "*.jar") })
}
