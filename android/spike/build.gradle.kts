plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "id.kelolauangku.spike"
    compileSdk = 35
    signingConfigs.getByName("debug") {
        storeFile = rootProject.file("../.cache/android/debug.keystore")
    }
    defaultConfig { applicationId = "id.kelolauangku.spike"; minSdk = 33; targetSdk = 35; versionCode = 1; versionName = "0.1"; ndk { abiFilters += "arm64-v8a" } }
    flavorDimensions += "engine"
    productFlavors {
        create("native") { dimension = "engine"; applicationIdSuffix = ".native" }
        create("withgo") { dimension = "engine"; applicationIdSuffix = ".withgo" }
    }
    buildTypes {
        release {
            isMinifyEnabled = true; isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug") // Benchmark only, never a production signing key.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "../app/proguard-rules.pro")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { add("withgoImplementation",files("../app/libs/core.aar")) }
