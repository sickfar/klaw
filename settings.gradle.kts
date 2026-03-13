rootProject.name = "klaw"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":common", ":gateway", ":engine", ":cli", ":e2e")
