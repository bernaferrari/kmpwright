plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false

    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
}

group = "io.github.bernaferrari"
version = providers.gradleProperty("releaseVersion").orElse("0.0.1").get()

// Keep release coordinates and metadata in one place. Individual library
// modules opt into maven-publish below, while examples remain private.
subprojects {
    group = rootProject.group
    version = rootProject.version
}

// One release invocation covers every published component, including all KMP
// target publications and the Android Compose library.
tasks.register("publishAndReleaseToMavenCentral") {
    dependsOn(
        ":kmpwright-core:publishAndReleaseToMavenCentral",
        ":kmpwright-compose:publishAndReleaseToMavenCentral"
    )
}

// Kotlin/JS (the web demo) uses the Node.js already on the machine instead of downloading one, so
// the build keeps resolving dependencies only from the repositories declared in settings.
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec>().download.set(false)
}
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec>().download.set(false)
}
