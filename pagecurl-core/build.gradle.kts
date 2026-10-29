// This module packages geometry and interaction logic as an Android library.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish.base")
    id("org.jetbrains.dokka")
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

// Generate API documentation from the English KDoc instead of publishing an empty archive.
dokka {
    dokkaPublications.configureEach {
        // Generate from local sources without waiting for external documentation indexes.
        offlineMode.set(true)
    }
    dokkaSourceSets.configureEach {
        // The public API has no Android SDK types, so its external package indexes are unnecessary.
        enableAndroidDocumentationLink.set(false)
    }
}

val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    dependsOn("dokkaGeneratePublicationHtml")
    from(tasks.named("dokkaGeneratePublicationHtml").map { it.outputs.files })
    from(rootProject.file("LICENSE")) { into("META-INF") }
}

// Local builds remain usable without credentials. CI provides signing keys only for releases.
mavenPublishing {
    publishToMavenCentral(automaticRelease = false)
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
}

val verifyCentralPublishing by tasks.registering {
    group = "publishing"
    description = "Checks release credentials and version before uploading to Maven Central."
    doLast {
        listOf("mavenCentralUsername", "mavenCentralPassword", "signingInMemoryKey").forEach { key ->
            check(!providers.gradleProperty(key).orNull.isNullOrBlank()) {
                "Missing $key. See PUBLISHING.md for the required environment variables."
            }
        }
        check(!project.version.toString().endsWith("-SNAPSHOT")) {
            "This release workflow requires a non-SNAPSHOT version."
        }
    }
}

tasks.matching {
    it.name == "publishToMavenCentral" || it.name == "publishAndReleaseToMavenCentral"
}.configureEach {
    dependsOn(verifyCentralPublishing)
}

// Coordinates come from gradle.properties; publish locally into build/repository.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = "pagecurl-core"
            version = project.version.toString()
            artifact(javadocJar)
            pom {
                name.set("PageCurl Core")
                description.set(
                    "Kotlin page curl geometry, gesture, layout and animation algorithms for Android."
                )
                url.set("https://github.com/Peyilo/PageCurl-Core")
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("Peyilo")
                        name.set("Peyilo")
                        email.set("peyilo.me@gmail.com")
                        url.set("https://peyilo.org")
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
