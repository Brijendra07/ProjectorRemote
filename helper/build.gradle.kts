plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.brijendra.projectorhelper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.brijendra.projectorhelper"
        minSdk = 26
        // 33 keeps foreground-service rules simple on the projector (Android 14).
        targetSdk = 33
        versionCode = 5
        versionName = "5.0"
    }

    // Fixed signing key so new builds install over old ones (keeps the pairing).
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

base {
    archivesName.set("ProjectorHelper")
}
