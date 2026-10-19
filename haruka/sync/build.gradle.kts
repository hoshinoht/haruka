// Haruka library sync: records, codec, merge engine, base store and remote stores. No app or upstream module
// dependencies; app glue in app/src/main/java/haruka/sync converts between these records and Mihon's models.
plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)

    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "haruka.sync"
}

kotlin {
    compilerOptions {
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.okhttp.core)

    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Literal coordinates keep the upstream version catalog untouched; kotest matches libs.versions.toml
    // kotest-assertions, mockwebserver3 matches okhttp.
    testImplementation("io.kotest:kotest-property:6.2.6")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.0")
}
