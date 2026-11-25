plugins {
    id("org.jetbrains.kotlin.multiplatform") version "2.4.21"
    id("com.android.kotlin.multiplatform.library") version "9.4.1"
}

val kmpwrightVersion = providers.gradleProperty("kmpwrightVersion")

kotlin {
    jvm()
    android {
        namespace = "io.github.bernaferrari.kmpwright.kmpsmoke"
        compileSdk { version = release(37) { minorApiLevel = 2 } }
        minSdk = 26
    }
    if (System.getProperty("os.name").startsWith("Mac")) {
        iosSimulatorArm64()
        iosArm64()
    }
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.bernaferrari:kmpwright-core:${kmpwrightVersion.get()}")
        }
    }
}
