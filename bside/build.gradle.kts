plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystoreFile = listOf(
    rootProject.file("debug.keystore"),
    rootProject.file("app/debug.keystore"),
    rootProject.file("bside/debug.keystore")
).firstOrNull { it.exists() }

android {
    namespace = "com.appathy.sugoroku.human"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.appathy.sugoroku.human"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
    }

    if (keystoreFile != null) {
        signingConfigs {
            getByName("debug") {
                storeFile = keystoreFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        noCompress += "mp4"
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }
}
