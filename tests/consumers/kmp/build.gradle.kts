plugins {
    id("org.jetbrains.kotlin.multiplatform") version "2.4.21"
}

val kmpwrightVersion = providers.gradleProperty("kmpwrightVersion").orElse("0.0.1")

kotlin {
    jvm()
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.bernaferrari:kmpwright-core:${kmpwrightVersion.get()}")
        }
    }
}
