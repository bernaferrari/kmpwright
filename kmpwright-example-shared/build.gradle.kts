plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "com.bernaferrari.kmpwright.example.shared"
        compileSdk = 37
        minSdk = 26
        withHostTest {}
    }

    iosSimulatorArm64("iosSim") {
        binaries.framework {
            baseName = "KmpwrightExampleShared"
            isStatic = true
        }
    }

    iosArm64("iosDevice") {
        binaries.framework {
            baseName = "KmpwrightExampleShared"
            isStatic = true
        }
    }

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.ui)
                implementation(libs.compose.ui.graphics)
                implementation(libs.compose.material3)
                implementation(libs.compose.material.icons.extended)
                implementation(libs.compose.resources)
                implementation(project(":kmpwright-core"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        val commonTest = getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val androidMain = getByName("androidMain") {
            dependencies {
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.activity.compose)
                implementation(libs.kotlinx.coroutines.android)
            }
        }

        val iosMain = create("iosMain") {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        val iosSimMain = getByName("iosSimMain") {
            dependsOn(iosMain)
        }

        val iosDeviceMain = getByName("iosDeviceMain") {
            dependsOn(iosMain)
        }

    }
}
