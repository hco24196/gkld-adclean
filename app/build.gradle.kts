plugins {
    id("com.android.application")
}

android {
    namespace = "com.gkld.adclean"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gkld.adclean"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "1.5.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // LSPosed / EdXposed 通用 Xposed API（只编译期使用，不打包进 APK）
    compileOnly("de.robv.android.xposed:api:82")
}
