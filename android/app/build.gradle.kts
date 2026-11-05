plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    ndkVersion = "27.2.12479018"
    namespace = "org.luma.music"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    buildFeatures { buildConfig = true }
    signingConfigs {
        create("persistent") {
            val keyPath = System.getenv("LUMA_KEYSTORE_PATH")
            if (!keyPath.isNullOrBlank()) {
                storeFile = file(keyPath)
                storePassword = System.getenv("LUMA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LUMA_KEY_ALIAS")
                keyPassword = System.getenv("LUMA_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (!System.getenv("LUMA_KEYSTORE_PATH").isNullOrBlank())
                signingConfig = signingConfigs.getByName("persistent")
        }
    }
    defaultConfig {
        val oauthClient = (System.getenv("LUMA_YANDEX_CLIENT_ID") ?: "").trim()
        require(oauthClient.isEmpty() || oauthClient.matches(Regex("[A-Za-z0-9_-]{8,128}"))) {
            "Invalid LUMA_YANDEX_CLIENT_ID"
        }
        buildConfigField("String", "YANDEX_CLIENT_ID", "\"$oauthClient\"")
        val sourceCommit = System.getenv("LUMA_SOURCE_COMMIT") ?: System.getenv("GITHUB_SHA") ?: "local"
        require(sourceCommit == "local" || sourceCommit.matches(Regex("[a-f0-9]{40}")))
        buildConfigField("String", "SOURCE_COMMIT", "\"$sourceCommit\"")
        applicationId = "org.luma.music"
        testInstrumentationRunner = "org.luma.music.RC8Instrumentation"
        minSdk = 30
        targetSdk = 36
        versionCode = 17
        versionName = "0.5.0-rc.8"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
