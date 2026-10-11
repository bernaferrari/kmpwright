import com.vanniktech.maven.publish.DeploymentValidation

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.maven.publish)
}

description = "Kotlin Multiplatform browser automation core"
val releaseSigningRequired = providers.gradleProperty("releaseSigningRequired").map(String::toBoolean).orElse(false)

// Vanniktech provides the Central Portal repository, automatic sources and
// javadoc jars, and in-memory signing from ORG_GRADLE_PROJECT_* properties.
mavenPublishing {
    coordinates("io.github.bernaferrari", "kmpwright-core", version.toString())
    publishToMavenCentral(
        automaticRelease = true,
        validateDeployment = DeploymentValidation.PUBLISHED
    )
    // Local publication/smoke tests intentionally remain secret-free. The
    // release workflow sets this property after validating its signing key.
    if (releaseSigningRequired.get()) signAllPublications()
    pom {
        name.set("Kmpwright Core")
        description.set("Kotlin Multiplatform browser automation core")
        inceptionYear.set("2026")
        url.set("https://github.com/bernaferrari/kmpwright")
        licenses { license { name.set("MIT License"); url.set("https://opensource.org/license/mit"); distribution.set("repo") } }
        developers { developer { id.set("bernaferrari"); name.set("Bernardo Ferrari"); email.set("bernaferrari@users.noreply.github.com"); url.set("https://github.com/bernaferrari") } }
        scm {
            connection.set("scm:git:git://github.com/bernaferrari/kmpwright.git")
            developerConnection.set("scm:git:ssh://git@github.com/bernaferrari/kmpwright.git")
            url.set("https://github.com/bernaferrari/kmpwright")
        }
    }
}

publishing {
    repositories { maven { name = "localTest"; url = rootProject.layout.buildDirectory.dir("test-repository").get().asFile.toURI() } }
}

// Package the two explicitly supported iOS slices into the stable public
// KmpwrightCore name. The built-in XCFramework helper derives its bundle name
// from the Gradle project (kmpwright-core), while Swift consumers need the
// framework/module name to remain KmpwrightCore.
val kmpwrightCoreXCFramework = layout.buildDirectory.dir("XCFrameworks/release/KmpwrightCore.xcframework")
tasks.register("packageKmpwrightCoreReleaseXCFramework") {
    dependsOn(
        "linkReleaseFrameworkIosDevice",
        "linkReleaseFrameworkIosSim"
    )
    inputs.files(
        layout.buildDirectory.dir("bin/iosDevice/releaseFramework"),
        layout.buildDirectory.dir("bin/iosSim/releaseFramework")
    )
    outputs.dir(kmpwrightCoreXCFramework)
    doLast {
        val deviceFramework = layout.buildDirectory.file("bin/iosDevice/releaseFramework/KmpwrightCore.framework").get().asFile
        val armSimulatorFramework = layout.buildDirectory.file("bin/iosSim/releaseFramework/KmpwrightCore.framework").get().asFile
        kmpwrightCoreXCFramework.get().asFile.deleteRecursively()
        fun runCommand(vararg command: String) {
            val exitCode = ProcessBuilder(command.toList()).inheritIO().start().waitFor()
            check(exitCode == 0) { "Command failed ($exitCode): ${command.joinToString(" ")}" }
        }
        runCommand(
            "xcodebuild", "-create-xcframework",
            "-framework", deviceFramework.absolutePath,
            "-framework", armSimulatorFramework.absolutePath,
            "-output", kmpwrightCoreXCFramework.get().asFile.absolutePath
        )
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm {
        testRuns["test"].executionTask.configure {
            useJUnit()
        }
    }

    android {
        namespace = "com.bernaferrari.kmpwright.core"
        compileSdk = 37
        minSdk = 26
        withHostTest { }
    }

    val iosSimTarget = iosSimulatorArm64("iosSim") {
        binaries.framework {
            baseName = "KmpwrightCore"
            isStatic = true
        }
    }

    val iosDeviceTarget = iosArm64("iosDevice") {
        binaries.framework {
            baseName = "KmpwrightCore"
            isStatic = true
        }
    }

    data class NativeCryptoTarget(
        val target: org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget,
        val sdk: String,
        val arch: String,
        val deploymentFlag: String
    )

    listOf(
        NativeCryptoTarget(iosSimTarget, "iphonesimulator", "arm64", "-mios-simulator-version-min=15.0"),
        NativeCryptoTarget(iosDeviceTarget, "iphoneos", "arm64", "-mios-version-min=15.0")
    ).forEach { native ->
        val suffix = native.target.name.replaceFirstChar { it.uppercase() }
        val nativeDir = layout.buildDirectory.dir("nativeCrypto/${native.target.name}")
        val objectFile = nativeDir.map { it.file("KmpwrightSnapshot.o") }
        val libraryFile = nativeDir.map { it.file("libKmpwrightCrypto.a") }
        val compileCrypto = tasks.register<Exec>("compileKmpwrightCrypto$suffix") {
            inputs.file("src/nativeInterop/cinterop/KmpwrightSnapshot.m")
            inputs.file("src/nativeInterop/cinterop/KmpwrightSnapshot.h")
            outputs.file(objectFile)
            doFirst { nativeDir.get().asFile.mkdirs() }
            commandLine(
                "xcrun", "--sdk", native.sdk, "clang",
                "-arch", native.arch, native.deploymentFlag, "-fobjc-arc",
                "-c", project.file("src/nativeInterop/cinterop/KmpwrightSnapshot.m").absolutePath,
                "-o", objectFile.get().asFile.absolutePath
            )
        }
        val archiveCrypto = tasks.register<Exec>("archiveKmpwrightCrypto$suffix") {
            dependsOn(compileCrypto)
            inputs.file(objectFile)
            outputs.file(libraryFile)
            commandLine(
                "xcrun", "libtool", "-static",
                objectFile.get().asFile.absolutePath,
                "-o", libraryFile.get().asFile.absolutePath
            )
        }
        val interop = native.target.compilations.getByName("main").cinterops.create("kmpwrightCrypto") {
            defFile(project.file("src/nativeInterop/cinterop/KmpwrightSnapshot.def"))
            packageName("com.bernaferrari.kmpwright.core.nativeinterop")
            includeDirs(project.file("src/nativeInterop/cinterop"))
            extraOpts(
                "-libraryPath", nativeDir.get().asFile.absolutePath,
                "-staticLibrary", libraryFile.get().asFile.name
            )
        }
        tasks.named(interop.interopProcessingTaskName).configure { dependsOn(archiveCrypto) }
    }

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.encoding)
                implementation(libs.ksoup)
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.atomicfu)
                implementation(libs.kotlinx.collections.immutable)
            }
        }

        val commonTest = getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                api(libs.kotlinx.coroutines.core)
            }
        }

        val jvmMain = getByName("jvmMain") {
            dependencies {
                implementation(libs.ktor.client.okhttp)
                api(libs.kotlinx.coroutines.core)
            }
        }

        val jvmTest = getByName("jvmTest") {
            dependencies {
                implementation(libs.junit)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        val androidMain = getByName("androidMain") {
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.webkit)
                implementation(libs.kotlinx.coroutines.android)
            }
        }

        val iosMain = create("iosMain") {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.ktor.client.darwin)
                api(libs.kotlinx.coroutines.core)
            }
        }

        val iosSimMain = getByName("iosSimMain") {
            dependsOn(iosMain)
        }

        val iosDeviceMain = getByName("iosDeviceMain") {
            dependsOn(iosMain)
        }

        // Keep iOS-only behavioral contracts separate from JVM tests while
        // sharing the common test fixtures and contract helpers.
        val iosTest = create("iosTest") {
            dependsOn(commonTest)
        }

        val iosSimTest = getByName("iosSimTest") {
            dependsOn(iosTest)
        }

        val iosDeviceTest = getByName("iosDeviceTest") {
            dependsOn(iosTest)
        }
    }
}
