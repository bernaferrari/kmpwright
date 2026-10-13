import com.vanniktech.maven.publish.DeploymentValidation

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    alias(libs.plugins.maven.publish)
}

description = "Jetpack Compose helpers for Kmpwright"
val releaseSigningRequired = providers.gradleProperty("releaseSigningRequired").map(String::toBoolean).orElse(false)

mavenPublishing {
    coordinates("io.github.bernaferrari", "kmpwright-compose", version.toString())
    publishToMavenCentral(
        automaticRelease = true,
        validateDeployment = DeploymentValidation.PUBLISHED
    )
    // Local publication/smoke tests intentionally remain secret-free. The
    // release workflow sets this property after validating its signing key.
    if (releaseSigningRequired.get()) signAllPublications()
    pom {
        name.set("Kmpwright Compose")
        description.set("Jetpack Compose helpers for Kmpwright")
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

android {
    namespace = "com.bernaferrari.kmpwright.compose"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        minSdk = 26

    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = true
        targetSdk = 37
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    // Every public declaration states its visibility. (KGP's ABI validation does not see AGP's
    // built-in Kotlin compilation yet, so the binary API dump is kept for kmpwright-core only.)
    explicitApi()

    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Public signatures expose core types (Page, KmpwrightConfig, NavigationResult).
    api(project(":kmpwright-core"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    // BackHandler
    implementation(libs.androidx.activity.compose)
}
