plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.proxy"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.proxy"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
