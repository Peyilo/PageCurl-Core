// Plugin resolution is separate from the libraries used by module source code.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Keep dependency repositories centralized; modules must not add their own.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PageCurl-Core"

include(":pagecurl-core")
