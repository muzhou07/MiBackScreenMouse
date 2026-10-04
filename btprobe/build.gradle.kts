// 蓝牙鼠标独立测试模块（不依赖主 App，验证通过后再并入）
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "mz.mibackscreen.btprobe"
    compileSdk = 37

    defaultConfig {
        applicationId = "mz.mibackscreen.btprobe"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        // 测试包用默认 debug 签名即可
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
