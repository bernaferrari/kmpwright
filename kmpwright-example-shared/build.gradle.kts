plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    compilerOptions {
        // The sample showcases Material 3 Expressive, which is still experimental.
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }

    android {
        namespace = "com.bernaferrari.kmpwright.example.shared"
        compileSdk {
            version = release(37) { minorApiLevel = 2 }
        }
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
                implementation(libs.material.kolor)
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
                // Newest Material 3 Expressive on Android; overrides the JetBrains redirect.
                implementation(libs.androidx.material3.expressive)
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
