import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ═══════════════════════════════════════════════════════════════════
// 加载 .env 文件（若存在），用于研发调试时预设 AI 大模型配置
// .env 已被 .gitignore 忽略，CI 环境无此文件时默认值为空字符串
// ═══════════════════════════════════════════════════════════════════
val envFile = rootProject.file(".env")
val envVars = mutableMapOf<String, String>()
if (envFile.exists()) {
    println("⚡ 加载 .env 文件: ${envFile.absolutePath}")
    envFile.forEachLine { line ->
        val trimmed = line.trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx > 0) {
                val key = trimmed.substring(0, eqIdx).trim()
                val value = trimmed.substring(eqIdx + 1).trim()
                if (key.isNotEmpty()) {
                    envVars[key] = value
                }
            }
        }
    }
}
fun envDefault(key: String): String = envVars[key] ?: ""

android {
    namespace = "me.geekabe.aitiao"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "me.geekabe.aitiao"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 从 .env 注入 AI 大模型配置默认值
        buildConfigField("String", "AITIAO_MODEL_ID", "\"${envDefault("AITIAO_MODEL_ID")}\"")
        buildConfigField("String", "AITIAO_API_KEY", "\"${envDefault("AITIAO_API_KEY")}\"")
        buildConfigField("String", "AITIAO_BASE_URL", "\"${envDefault("AITIAO_BASE_URL")}\"")
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("aitiao-release.jks")
            storePassword = "aitiao123"
            keyAlias = "aitiao"
            keyPassword = "aitiao123"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)

    // Compose BOM — 统一管理所有 Compose 库版本
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
