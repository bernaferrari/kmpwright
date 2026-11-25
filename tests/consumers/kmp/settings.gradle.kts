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
        exclusiveContent {
            forRepository { maven { url = uri("../../../build/test-repository") } }
            filter { includeGroup("io.github.bernaferrari") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "kmpwright-kmp-consumer"
