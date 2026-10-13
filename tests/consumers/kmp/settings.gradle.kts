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
        mavenCentral()
    }
}

rootProject.name = "kmpwright-kmp-consumer"
