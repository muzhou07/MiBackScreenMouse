import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------- 正式签名
// 签名信息放在仓库根目录的 keystore.properties（已 gitignore，不入库）。
// 构建 release 时若缺密钥会直接失败（见文末校验），避免误发出 debug 签名的包；
// debug 构建不受影响，新 clone 依然可以直接调试。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val releaseKeyFile: java.io.File? = keystoreProps
    .getProperty("storeFile")
    ?.let { rootProject.file(it) }
    ?.takeIf { it.isFile }

android {
    namespace = "mz.mibackscreen.mouse"
    compileSdk = 37

    defaultConfig {
        applicationId = "mz.mibackscreen.mouse"
        minSdk = 35
        targetSdk = 37
        versionCode = 6
        versionName = "1.0.3"
    }

    signingConfigs {
        releaseKeyFile?.let { keyFile ->
            create("release") {
                storeFile = keyFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // minSdk 35 不需要 JAR(v1) 签名；用 v2（兼容性最好）。
                // 需要支持密钥轮换时再加 enableV3Signing = true（AGP 会自动改为只签 v3）。
                enableV1Signing = false
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            // 不混淆
            isMinifyEnabled = false
            isShrinkResources = false
            // 正式包必须有正式密钥：缺密钥时不配置签名（assembleRelease 会在文末自检里直接失败），
            // 这样既不会"悄悄退回 debug 签名"，也不影响 assembleDebug。
            if (releaseKeyFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // 让 native 库被解压到 nativeLibraryDir：root 助手要以可执行文件方式运行
        jniLibs.useLegacyPackaging = true
    }
}

// ---------------------------------------------------------------- root 助手(bsm_helper)

/** 交叉编译 bsm_helper.c 到 jniLibs/<abi>/libbsm_helper.so，随 APK 打包。 */
val helperAbi = "arm64-v8a"
val helperApiLevel = 35
val helperNdkRevision = "29.0.14206865"

val buildRootHelper = tasks.register("buildRootHelper") {
    group = "build"
    description = "编译 root 助手 bsm_helper"

    val sdkDir: File = run {
        val props = Properties()
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { props.load(it) }
        val dir = props.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")
        requireNotNull(dir) { "未配置 Android SDK：请在 local.properties 里写 sdk.dir" }
        File(dir)
    }
    val hostTag = if (System.getProperty("os.name").lowercase().contains("win")) {
        "windows-x86_64"
    } else {
        "linux-x86_64"
    }
    val clang = File(
        sdkDir,
        "ndk/$helperNdkRevision/toolchains/llvm/prebuilt/$hostTag/bin/aarch64-linux-android${helperApiLevel}-clang",
    )
    val srcFile = layout.projectDirectory.file("src/main/cpp/bsm_helper.c").asFile
    val outDir = layout.projectDirectory.dir("src/main/jniLibs/$helperAbi").asFile
    val outFile = File(outDir, "libbsm_helper.so")

    // 调试开关（--no-auth/--tcp/--token/--no-grab）只在 debug 变体的助手里编译进去。
    // 同时请求 debug+release（如 `build`）时按安全侧处理：不带调试开关。
    val wantDebugTools = gradle.startParameter.taskNames.any {
        it.contains("debug", ignoreCase = true)
    } && gradle.startParameter.taskNames.none {
        it.contains("release", ignoreCase = true)
    }

    inputs.file(srcFile)
    inputs.property("debugTools", wantDebugTools)
    outputs.file(outFile)

    doLast {
        require(clang.isFile) { "找不到交叉编译器: ${clang.absolutePath}（检查 NDK $helperNdkRevision）" }
        outDir.mkdirs()
        val args = mutableListOf(
            clang.absolutePath,
            "-O2", "-std=c11", "-Wall", "-Wno-unused-parameter", "-Wno-unused-result",
        )
        if (wantDebugTools) args += "-DBSM_DEBUG_TOOLS"
        args += listOf(srcFile.absolutePath, "-o", outFile.absolutePath)
        project.providers.exec {
            workingDir = projectDir
            commandLine(args)
        }.result.get().assertNormalExitValue()
        logger.lifecycle(
            "bsm_helper -> ${outFile.relativeTo(rootDir)} (${outFile.length()} bytes" +
                (if (wantDebugTools) ", debug tools ON" else ", debug tools OFF") + ")"
        )
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(buildRootHelper)
}

// ---------------------------------------------------------------- 发布前自检
// 打正式包必须带正式密钥：缺 keystore.properties / 密钥文件时直接失败（debug 构建不受影响）。
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    doFirst {
        check(keystorePropsFile.isFile && releaseKeyFile != null) {
            "缺少正式签名：请在仓库根目录提供 keystore.properties 与密钥文件（release.jks）后重试"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material3)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)

    debugImplementation(libs.compose.ui.tooling)
}
