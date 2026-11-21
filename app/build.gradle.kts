plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI's emulator tests build with -Pvidgrab.abis=x86_64 (the engine ships that too). Phones are ARM.
val abiOverride = (project.findProperty("vidgrab.abis") as String?)?.split(",")?.map { it.trim() }

// A fixed signing key, so a new build installs over the previous one (keeping the list and the updated
// engine). CI writes it from the VIDGRAB_KEYSTORE secret (see ci/build.sh and the README). Without it
// the build uses Android's temporary debug key.
val fixedKey = System.getenv("VIDGRAB_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.example.vidgrab"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.vidgrab"
        minSdk = 29
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (abiOverride != null) ndk { abiFilters += abiOverride }
    }
    // One APK per phone type: each is about half the size of one APK holding both.
    // arm64-v8a fits nearly every phone from the last 8 years; armeabi-v7a is for older 32-bit ones.
    splits {
        abi {
            isEnable = abiOverride == null
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }
    signingConfigs {
        if (fixedKey != null) create("fixed") {
            storeFile = fixedKey
            storeType = "pkcs12"
            storePassword = System.getenv("VIDGRAB_KEYSTORE_PASSWORD") ?: "vidgrab"
            keyAlias = "vidgrab"
            keyPassword = System.getenv("VIDGRAB_KEYSTORE_PASSWORD") ?: "vidgrab"
        }
    }
    buildTypes {
        getByName("debug") {
            if (fixedKey != null) signingConfig = signingConfigs.getByName("fixed")
        }
    }
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
}
dependencies {
    // 0.18.1 bundles yt-dlp 2025.11.12 + Python 3.12 + QuickJS (needed for YouTube); 0.17.4 bundled a
    // 2024 yt-dlp that YouTube and other sites no longer accept. The engine also updates itself daily.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")

    // End-to-end tests on an emulator (app/src/androidTest, run by CI)
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
