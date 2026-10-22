plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.mikepenz.aboutlibraries.plugin.android")
}

android {
    namespace = "com.ivor.ivormusic"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        manifestPlaceholders["appLabel"] = "@string/app_name"
        buildConfigField("String", "APPLICATION_ID", "\"dev.foldman\"")
        buildConfigField("String", "GITHUB_REPO", "\"ivorisnoob/Koda\"")
        buildConfigField("String", "GITHUB_USERNAME", "\"ivorisnoob\"")
        buildConfigField("String", "VERSION_NAME", "\"0.1.2\"")
        buildConfigField("int", "VERSION_CODE", "3")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }
}

dependencies {
    coreLibraryDesugaring(kodaLibs.desugar.jdk.libs.nio)

    implementation(kodaLibs.androidx.core.ktx)
    implementation(kodaLibs.androidx.lifecycle.runtime.ktx)
    implementation(kodaLibs.androidx.core.splashscreen)
    implementation(kodaLibs.androidx.activity.compose)
    implementation(platform(kodaLibs.androidx.compose.bom))
    implementation(kodaLibs.androidx.compose.ui)
    implementation(kodaLibs.androidx.compose.ui.graphics)
    implementation(kodaLibs.androidx.compose.material3)
    implementation(kodaLibs.material.color.utilities)
    implementation(kodaLibs.androidx.media3.exoplayer)
    implementation(kodaLibs.androidx.media3.exoplayer.dash)
    implementation(kodaLibs.androidx.media3.exoplayer.hls)
    implementation(kodaLibs.androidx.media3.ui)
    implementation(kodaLibs.androidx.media3.session)
    implementation(kodaLibs.androidx.media3.effect)
    implementation(kodaLibs.androidx.navigation.compose)
    implementation(kodaLibs.androidx.lifecycle.viewmodel.compose)
    implementation(kodaLibs.androidx.compose.material.icons.extended)
    implementation(kodaLibs.coil.compose)
    implementation(kodaLibs.androidx.glance.appwidget)
    implementation(kodaLibs.androidx.glance.material3)
    implementation(kodaLibs.androidx.work.runtime.ktx)
    implementation(kodaLibs.aboutlibraries.compose.m3)
    implementation(kodaLibs.androidx.graphics.shapes)
    implementation(kodaLibs.androidx.ui.text.google.fonts)
    implementation(kodaLibs.androidx.palette)
    implementation(kodaLibs.newpipe.extractor) {
        exclude(group = "org.mozilla", module = "rhino-engine")
    }
    implementation(kodaLibs.okhttp)
    implementation(kodaLibs.kotlinx.serialization.json)
    implementation(kodaLibs.androidx.security.crypto)
    implementation(kodaLibs.kotlinx.coroutines.guava)
    implementation(kodaLibs.jaudiotagger)
}
