plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.ghsssss.ithomeadkill"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.ghsssss.ithomeadkill"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    // Xposed API 只在编译期使用，打出的 APK 不包含它
    compileOnly("de.robv.android.xposed:api:82")
}
