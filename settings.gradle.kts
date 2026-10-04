pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BackScreenMouse"
include(":app")

// 独立的蓝牙鼠标测试模块：自带包名与界面，验证通过后再把代码并入 :app
include(":btprobe")
