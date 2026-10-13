plugins {
    id("com.android.application")
}

val kmpwrightVersion = providers.gradleProperty("kmpwrightVersion").orElse("0.1.0")

android {
    namespace = "io.github.bernaferrari.kmpwright.smoke"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "io.github.bernaferrari.kmpwright.smoke"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    implementation("io.github.bernaferrari:kmpwright-core:${kmpwrightVersion.get()}")
    implementation("io.github.bernaferrari:kmpwright-compose:${kmpwrightVersion.get()}")
}
