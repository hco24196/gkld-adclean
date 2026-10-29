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
        // Xposed API 官方仓库（提供 de.robv.android.xposed:api:82）
        maven { url = uri("https://api.xposed.info/") }
    }
}

rootProject.name = "GkldAdClean"
include(":app")
