import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.ghsssss.ithomeadkill"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "io.github.ghsssss.ithomeadkill"
        minSdk = 26
        targetSdk = 34
        // 版本号来源优先级：-PverCode/-PverName > 环境变量 > 本地默认值
        val vName = (project.findProperty("verName") as String?)
            ?: System.getenv("VERSION_NAME")
            ?: "1.1-dev"
        val vCode = ((project.findProperty("verCode") as String?)
            ?: System.getenv("VERSION_CODE")
            ?: "2").toInt()
        versionName = vName
        versionCode = vCode
    }

    buildTypes {
        release {
            // Published builds previously used the debug signer. Preserve update compatibility.
            signingConfig = signingConfigs.getByName("debug")
            optimization {
                enable = true
            }
        }
    }

    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.5-beta03")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.5-beta03")
    testImplementation("junit:junit:4.13.2")
    // API classes are supplied by the framework, the service client by this app.
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
}
