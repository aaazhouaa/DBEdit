plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.dbedit"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.dbedit"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // release 复用 debug 签名，产出可直接 adb install 的包。
            // 有正式 keystore 时优先用正式（v2/v3），没有也不会产出 unsigned。
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    testImplementation("junit:junit:4.13.2")
    // Robolectric：在 JVM 上跑真实 Android 框架。
    // 用途：真正加载布局、跑真实 Activity 生命周期（这些不需要 SQLite）。
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    // 仅测试用：本地 jar（用真实 SQLite 引擎验证生成的 SQL）
    testImplementation(
        files(
            "testlibs/sqlite-jdbc-3.45.3.0.jar",
            "testlibs/slf4j-api-1.7.36.jar"
        )
    )
}
