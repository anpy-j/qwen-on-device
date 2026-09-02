plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps: Map<String, String> = rootProject.file("local.properties").takeIf { it.exists() }
    ?.readLines()
    ?.mapNotNull { line ->
        val sep = line.indexOf('=')
        if (sep > 0) line.substring(0, sep).trim() to line.substring(sep + 1).trim() else null
    }
    ?.toMap()
    ?: emptyMap()

android {
    namespace = "com.example.qwenondevice"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.qwenondevice"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    if (localProps["keyAlias"] != null && file("release.jks").exists()) {
        signingConfigs {
            create("release") {
                storeFile = file("release.jks")
                storePassword = localProps["storePassword"].orEmpty()
                keyAlias = localProps["keyAlias"].orEmpty()
                keyPassword = localProps["keyPassword"].orEmpty()
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        noCompress += listOf("task", "bin", "onnx", "txt")
    }
}

dependencies {
    // 0.10.24+ 的 arm64 引擎带 SME2 内核，在 Apple Silicon 宿主机的模拟器上
    // 因 qemu 谎报 sme2 特性导致 SIGILL (mediapipe#6293)；0.10.23 无 SME2 内核，可用
    implementation("com.google.mediapipe:tasks-genai:0.10.23")

    // Sherpa-ONNX 离线语音识别引擎
    implementation("com.bihe0832.android:lib-sherpa-onnx:8.6.6")

    // Material Design 3 主题与颜色体系
    implementation("com.google.android.material:material:1.12.0")
}
