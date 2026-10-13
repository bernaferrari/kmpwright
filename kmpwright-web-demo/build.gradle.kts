plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// The live demo on the docs site: KMPwright's real Kotlin engine compiled to JavaScript, driving a
// same-origin <iframe>. Website commands generate the bundle in the ignored
// website/lib/kmpwright-demo directory; only Kotlin source is committed.
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
