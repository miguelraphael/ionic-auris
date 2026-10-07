plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * Test-only extension APK.
 *
 * This module exists to prove the extension pipeline end to end: it is compiled against the
 * same `:source-api` classes the app ships, produces a real APK containing real `classes.dex`,
 * and is loaded at runtime through `DexClassLoader` — the same path a production Aniyomi
 * extension takes.
 *
 * It is an **application** module, not a library, because `DexClassLoader` needs a file
 * containing `classes.dex`. An AAR ships `classes.jar`, which the VM will not load.
 *
 * It has no launcher activity and is not referenced by `:app` outside tests, so it never ships
 * to users.
 */
android {
    namespace = "com.auris.app.mockextension"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.auris.app.mockextension"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // The extension's own classes are tiny, so they fit in the primary dex. Without
        // this the build may emit classes2.dex..classesN.dex depending on what the
        // dependency graph pulls in, and the tests' dex enumeration would have to handle
        // a layout that never occurs in a real extension APK.
        multiDexEnabled = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        debug {
            // No minification: a stripped/renamed class set would make discovery depend on
            // keep rules, which would test the rules rather than the loader.
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    // Real extension APKs are tiny — only their own classes, since `eu.kanade.tachiyomi.*`
    // and the HTTP stack come from the host. This module currently *bundles* `:source-api`,
    // which produced a 3 MB / 10-dex APK and put the sources in classes10.dex rather than
    // classes.dex. Excluding the API classes makes the artifact resemble a real extension:
    // one small dex holding only the extension's own classes, with the host API resolved
    // through the DexClassLoader's parent.
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }

    dependenciesInfo {
        // Don't inline dependency metadata; it only inflates the APK.
        includeInApk = false
        includeInBundle = false
    }


    // An extension APK is a library in practice; suppress the manifest merger complaints about
    // it declaring no launcher activity.
    lint {
        abortOnError = false
    }
}

dependencies {
    /**
     * `compileOnly`, not `implementation` — and this is the crux of the whole exercise.
     *
     * A real extension APK contains **only its own classes**. It compiles against the
     * `eu.kanade.tachiyomi.*` API and resolves it at runtime from the host's
     * DexClassLoader parent. Using `compileOnly` reproduces that exactly: the module cannot
     * package the API, so the resulting APK is a single small dex with the extension's own
     * classes in `classes.dex`, and every `eu.kanade.tachiyomi.*` reference must be
     * satisfied by the host at load time.
     *
     * If this were `implementation`, the test would still pass while proving nothing — the
     * APK would just carry its own copy of the API.
     */
    compileOnly(project(":source-api"))
}