// Pin Android and Kotlin plugin versions here so every module uses one toolchain.
plugins {
    id("com.android.library") version "8.11.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.vanniktech.maven.publish.base") version "0.34.0" apply false
    id("org.jetbrains.dokka") version "2.0.0" apply false
}
