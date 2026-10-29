// This module packages geometry and interaction logic as an Android library.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

group = providers.gradleProperty("GROUP").get()

version = providers.gradleProperty("VERSION_NAME").get()

android {
    namespace = "org.peyilo.pagecurl"
    compileSdk = 36

    // API 21 support is why binary16 conversion and compensated multiply-add
    // retain local fallbacks instead of newer Android framework methods.
    defaultConfig {
        minSdk = 21
    }

    // Keep Java bytecode and Kotlin JVM targets aligned for consuming applications.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    // Publish the release variant together with source code for downstream debugging.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// Coordinates come from gradle.properties; publish locally into build/repository.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = "pagecurl-core"
            version = project.version.toString()
            pom {
                name.set("PageCurl Core")
                description.set(
                    "Kotlin page curl geometry, gesture, layout and animation algorithms for Android."
                )
                url.set("https://github.com/Peyilo/PageCurl-Core")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                        distribution.set("repo")
                    }
                }
                scm {
                    url.set("https://github.com/Peyilo/PageCurl-Core")
                    connection.set("scm:git:https://github.com/Peyilo/PageCurl-Core.git")
                }
            }
            afterEvaluate { from(components["release"]) }
        }
    }
    repositories {
        maven {
            name = "local"
            url = uri(rootProject.layout.buildDirectory.dir("repository"))
        }
    }
}
