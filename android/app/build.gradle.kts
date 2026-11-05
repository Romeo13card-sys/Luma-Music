plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    ndkVersion = "27.2.12479018"
    namespace = "dev.romeo.yamusic"
    compileSdkVersion("android-37.0")
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "dev.romeo.yamusic"
        minSdk = 30
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0-test"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
