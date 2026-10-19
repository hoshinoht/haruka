// Haruka's pure logic: chapter dedup and grouping, release versions, titles. No app or upstream module dependencies.
// An Android library rather than plain Kotlin because it ships the official scanlator catalog as an asset.
plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)

    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "haruka.core"
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
