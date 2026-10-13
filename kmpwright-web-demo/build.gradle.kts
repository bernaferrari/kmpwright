plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// The live demo on the docs site: KMPwright's real Kotlin engine compiled to JavaScript, driving a
// same-origin <iframe>. `./gradlew :kmpwright-web-demo:syncWebsiteDemo` refreshes the committed copy
// in website/lib/kmpwright-demo, so the website builds without Gradle.
kotlin {
    js {
        outputModuleName = "kmpwright-demo"
        browser()
        useEsModules()
        generateTypeScriptDefinitions()
        binaries.library()
    }

    sourceSets {
        getByName("jsMain") {
            dependencies {
                implementation(project(":kmpwright-core"))
            }
        }
    }
}

tasks.register<Sync>("syncWebsiteDemo") {
    description = "Copies the production demo bundle into the website."
    from(tasks.named("jsBrowserProductionLibraryDistribution")) {
        include("*.mjs", "*.d.mts")
        // The source map stays in the build directory.
        filter { line -> if (line.startsWith("//# sourceMappingURL=")) "" else line }
    }
    into(rootProject.layout.projectDirectory.dir("website/lib/kmpwright-demo"))
}
