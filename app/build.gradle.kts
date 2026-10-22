plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.ktmrc390.obd"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.ktmrc390.obd"
        minSdk = 23
        targetSdk = 35
        versionCode = 3003
        versionName = "RC390_V003_003"
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
    kotlinOptions { jvmTarget = "17" }
}
