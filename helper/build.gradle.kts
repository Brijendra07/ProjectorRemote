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
        versionCode = 1
        versionName = "1.0"
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
