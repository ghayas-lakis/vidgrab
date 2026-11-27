plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// One APK, for 64-bit ARM: nearly every phone from the last 8 years. Each extra processor type would make
// it about twice as big. CI's emulator tests build with -Pvidgrab.abis=x86_64; armeabi-v7a (old 32-bit
// phones) works the same way.
val abiOverride = (project.findProperty("vidgrab.abis") as String?)?.split(",")?.map { it.trim() }

// A fixed signing key, so a new build installs over the previous one (keeping the list and the updated
// engine). CI writes it from the VIDGRAB_KEYSTORE secret (see ci/build.sh and the README). Without it
// the build uses Android's temporary debug key.
val fixedKey = System.getenv("VIDGRAB_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "io.github.ghayaslakis.vidgrab"
    compileSdk = 34
    defaultConfig {
        applicationId = "io.github.ghayaslakis.vidgrab"
        minSdk = 29
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += abiOverride ?: listOf("arm64-v8a") }
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
    // The browser's proxy (Settings > Proxy / VPN): ProxyController
    implementation("androidx.webkit:webkit:1.8.0")

    // Unit tests of the pure logic (app/src/test, run by CI's build job): `gradle testDebugUnitTest`
    testImplementation("junit:junit:4.13.2")

    // End-to-end tests on an emulator (app/src/androidTest, run by CI)
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
