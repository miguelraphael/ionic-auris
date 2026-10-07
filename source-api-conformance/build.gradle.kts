plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

/**
 * Compile-time conformance guard for `:source-api`.
 *
 * ### Why this exists
 * `:mock-extension` compiles against `:source-api`, so it proves the layer is
 * *self-consistent* — it cannot detect that the layer has drifted from Anikku. If
 * `SEpisode.chapter_number` disappeared, or `getAnimeEpisodeUpdate` were wrongly still
 * abstract, every existing test would keep passing while real Anikku extensions failed to
 * load with `NoSuchMethodError` / `AbstractMethodError`.
 *
 * This module closes that gap by asserting the *exact* signatures extensions are compiled
 * against. It produces no artifact and ships nothing; a green build is the signal, and a red
 * build means an Anikku extension would not link.
 *
 * ### Why an Android library and not a JVM one
 * `:source-api` is an Android library, and Gradle will not resolve a JVM project against an
 * Android one. Matching the module type lets the conformance sources see exactly the same
 * declarations extensions do. It is never packaged or installed.
 *
 * ### How to extend it
 * When adopting a new Anikku API version, add the corresponding `conform*` declaration to
 * `AnikkuApiConformance.kt`. A failure reads as "this host cannot load extensions built
 * against Anikku X".
 */
android {
    namespace = "com.auris.app.conformance"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // `api`, not `implementation`: every assertion resolves through it.
    api(project(":source-api"))
}