import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersionCode = providers.gradleProperty("app.versionCode")
    .map(String::toInt)
    .getOrElse(1)
val appVersionName = providers.gradleProperty("app.versionName")
    .getOrElse("0.1.0")
val supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

val signingPropertiesFile = rootProject.file("signing.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.isFile) {
        signingPropertiesFile.inputStream().use(::load)
    }
}

fun signingProperty(name: String): String? =
    signingProperties.getProperty(name)?.trim()?.takeIf(String::isNotEmpty)

fun resolveSigningFile(path: String): File {
    val candidate = File(path)
    return if (candidate.isAbsolute) candidate else rootProject.file(path)
}

val releaseStoreFile = signingProperty("storeFile")?.let(::resolveSigningFile)
val hasReleaseSigning = releaseStoreFile?.isFile == true &&
    signingProperty("storePassword") != null &&
    signingProperty("keyAlias") != null &&
    signingProperty("keyPassword") != null

if (signingPropertiesFile.isFile && !hasReleaseSigning) {
    logger.warn("android/signing.properties is present but incomplete; release APK will be unsigned.")
}

android {
    namespace = "com.proxy"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.proxy"
        minSdk = 31
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        ndk {
            abiFilters += supportedAbis
        }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = releaseStoreFile
                storePassword = signingProperty("storePassword")
                keyAlias = signingProperty("keyAlias")
                keyPassword = signingProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    packaging {
        jniLibs {
            // The tunnel engine ships native executables (tun2socks, pdnsd)
            // packaged as lib*.so; they must be extracted to disk to be exec'd.
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
