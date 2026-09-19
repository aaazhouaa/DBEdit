pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        // 显式用 repo1 直连，避开本环境对 maven.apache.org 的 TLS 干扰
        maven { url = uri("https://repo1.maven.org/maven2") }
    }
}

rootProject.name = "DBEdit"
include(":app")
