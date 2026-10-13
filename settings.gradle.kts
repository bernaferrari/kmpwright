pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    val consumerAgp = providers.gradleProperty("kmpwrightConsumerAgp").orNull
    val consumerKotlin = providers.gradleProperty("kmpwrightConsumerKotlin").orNull
    if (consumerAgp != null || consumerKotlin != null) {
        // Override alias versions before plugin requests, rather than rewriting resolved plugins.
        defaultLibrariesExtensionName = "kmpwrightDefaults"
        versionCatalogs {
            create("libs") {
                from(files("gradle/libs.versions.toml"))
                consumerAgp?.let { version("agp", it) }
                consumerKotlin?.let { version("kotlin", it) }
                providers.gradleProperty("kmpwrightConsumerSerialization").orNull?.let { version("kotlinxSerializationJson", it) }
            }
        }
    }
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kmpwright"
include(":kmpwright-core")
if (!providers.gradleProperty("kmpwrightConsumerAgp").isPresent) {
    include(":kmpwright-compose")
    include(":kmpwright-test")
    include(":example-android")
    include(":kmpwright-example-shared")
    include(":kmpwright-web-demo")
}
