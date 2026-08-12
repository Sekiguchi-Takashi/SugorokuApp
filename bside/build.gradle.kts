plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * B面（学校編・人間キャラ）
 *
 * A面（:app）とは Gradle モジュールとして完全に独立している。
 * Kotlin・assets・res をすべて自前で持つので、A面をどう変更しても B面は影響を受けない。
 * そのぶん、A面のバグ修正は手作業で B面へ反映する必要がある。
 */
android {
    namespace = "com.appathy.sugorokub"
    compileSdk = 34

    defaultConfig {
        // A面と同時にインストールできるよう applicationId を分ける
        applicationId = "com.appathy.sugoroku.bside"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1-B"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
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

dependencies {
    // 外部依存ゼロ（規約）
}
