plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.vidgrab"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.vidgrab"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
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
}
