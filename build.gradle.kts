plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false

    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
}

group = "io.github.bernaferrari"
version = providers.gradleProperty("releaseVersion").orElse("0.1.0").get()

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
