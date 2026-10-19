plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.vidgrab.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vidgrab.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ABI Splits: Creates separate APKs for arm64-v8a and x86_64 to optimize APK size
    // isUniversalApk = true generates an all-in-one APK for broad testing alongside splits
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug") // Debug signing fallback for local builds
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Critical for Android 10+ W^X SELinux compliance:
    // Ensures native libraries (.so) are unpacked uncompressed into nativeLibraryDir
    // so youtubedl-android (libpython.so, libffmpeg.so, libaria2c.so, libqjs.so) can execute directly.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/DEPENDENCIES"
            )
        }
    }
}

dependencies {
    // Java 8/11 Desugaring for NewPipeExtractor NIO requirements
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // AndroidX Core & Lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Kotlin Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Room SQLite Database
    implementation(libs.bundles.room)
    ksp(libs.androidx.room.compiler)

    // WorkManager (Android 14/15/16 User-Initiated Data Transfer Engine)
    implementation(libs.androidx.work.runtime.ktx)

    // OkHttp (Shared HTTP Client)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Core Extraction Engine: youtubedl-android (yt-dlp, FFmpeg, Aria2c, QuickJS)
    implementation(libs.bundles.youtubedl)

    // Tier 2 Fast JVM Extractor: NewPipeExtractor
    implementation(libs.newpipe.extractor)

    // Diagnostics & Logging
    implementation(libs.timber)
}
