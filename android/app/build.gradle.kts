import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.blenderar.client"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.blenderar.client"
        // ARCore 最低支持 Android 7.0(API 24),与需求一致
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Play 对 16KB 页大小设备的要求(Android 15+,2026-01 起强制),
    // Filament 原生库随 APK 打包,应用侧同样需要声明(SceneView #2226)。
    experimentalProperties["android.nativeLibraryAlignmentPageSize"] = "16k"

    packagingOptions {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // UI(Jetpack Compose + Material 3,BOM 锁定)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)

    // AR 渲染栈:SceneView(封装 ARCore + Filament)
    implementation(libs.sceneview.ar)
    implementation(libs.arcore)

    // 网络:OkHttp(WebSocket + HTTP 下载)
    implementation(libs.okhttp)

    // 并发与序列化
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // 单元测试(纯 JVM,不依赖设备)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
