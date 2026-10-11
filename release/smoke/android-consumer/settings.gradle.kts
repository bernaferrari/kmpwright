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
        maven { url = uri("../../../build/test-repository") }
        google()
        mavenCentral()
    }
}

rootProject.name = "kmpwright-android-consumer"
include(":app")
