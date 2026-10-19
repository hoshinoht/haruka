package haruka.gradle

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Project
import java.util.Properties

/**
 * Haruka's changes to the :app build, applied from a single FORK hook in app/build.gradle.kts so upstream's
 * build script (including its version bumps) merges cleanly.
 *
 * - Identity and version come from app/src/haruka/version.properties and override upstream's DSL values in
 *   finalizeDsl, which runs after the build script body.
 * - Branding lives in variant source overlays under app/src/haruka/, which take priority over src/main and
 *   build-type source sets: res (every variant), res-release (release, foss), res-debug (debug, nightly).
 *   Benchmark builds keep their own src/benchmark/res label and launcher icon.
 * - Debug builds merge src/haruka/manifest-debug/AndroidManifest.xml (debug-only tools such as the Drive CAS
 *   self-test), so upstream's manifests stay untouched.
 * - Fork dependencies are pinned here rather than in upstream's version catalog, so catalog merges stay clean.
 */
fun configureHarukaApp(project: Project) {
    val version = Properties().apply {
        val file = project.layout.projectDirectory.file("src/haruka/version.properties")
        load(project.providers.fileContents(file).asText.get().reader())
    }
    fun versionProperty(name: String): String =
        version.getProperty(name) ?: error("src/haruka/version.properties has no $name")

    val androidComponents = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)

    androidComponents.finalizeDsl { android ->
        android.defaultConfig.applicationId = "app.haruka"
        android.defaultConfig.versionCode = versionProperty("versionCode").toInt()
        android.defaultConfig.versionName = versionProperty("versionName")
        // The upstream Mihon commit this build is based on, shown in About.
        android.defaultConfig.buildConfigField("String", "MIHON_VERSION", "\"${versionProperty("mihonBase")}\"")

        // Upstream gives benchmark builds the debug icon; Haruka Bench has its own in src/benchmark/res, and
        // both in one source set would be duplicate resources.
        android.sourceSets.getByName("benchmark").res.directories.remove("src/debug/res")
        // Fork rules live outside the generated baselineProfiles dir so regenerating never drops them.
        android.sourceSets.getByName("main").baselineProfiles.directories.add("src/haruka/baselineProfiles")
    }

    androidComponents.onVariants { variant ->
        variant.sources.res?.apply {
            addStaticSourceDirectory("src/haruka/res")
            when (variant.buildType) {
                "release", "foss" -> addStaticSourceDirectory("src/haruka/res-release")
                "debug", "nightly" -> addStaticSourceDirectory("src/haruka/res-debug")
            }
        }
        if (variant.buildType == "debug") {
            variant.sources.manifests.addStaticManifestFile("src/haruka/manifest-debug/AndroidManifest.xml")
        }
    }

    project.dependencies.add("implementation", project.project(":haruka:core"))
    project.dependencies.add("implementation", project.project(":haruka:sync"))
    project.dependencies.add("implementation", project.project(":i18n-fork"))
    // Google Identity AuthorizationClient for Drive sync (haruka.sync.auth).
    project.dependencies.add("implementation", "com.google.android.gms:play-services-auth:$PLAY_SERVICES_AUTH")
}

// Latest stable on Google Maven (play-services-auth maven-metadata.xml, checked 2026-10-19).
private const val PLAY_SERVICES_AUTH = "22.0.0"
