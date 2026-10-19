plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "id.kelolauangku.app"
    compileSdk = 35
    // Compile SDK-provided legacy instrumentation classes; no bundled test dependency.
    useLibrary("android.test.base")
    useLibrary("android.test.runner")
    signingConfigs.getByName("debug") {
        storeFile = rootProject.file("../.cache/android/debug.keystore")
    }
    defaultConfig {
        applicationId = "id.kelolauangku.app"
        minSdk = 33
        targetSdk = 35
        versionCode = 8
        versionName = "2.1.3-preview"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation(files("libs/core.aar")) }
