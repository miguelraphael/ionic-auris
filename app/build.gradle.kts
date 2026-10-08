plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.auris.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.auris.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // Supabase project credentials. Override per build type / via gradle.properties —
        // never commit a service_role key here.
        buildConfigField("String", "SUPABASE_URL", "\"${project.findProperty("SUPABASE_URL") ?: ""}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${project.findProperty("SUPABASE_ANON_KEY") ?: ""}\"")
        buildConfigField("String", "KITSU_BASE_URL", "\"https://kitsu.io/api/edge\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        /**
         * Skip `lintVitalRelease` during `assembleRelease`.
         *
         * Lint runs as a release-blocking step, and on a memory-constrained machine the
         * whole-project analysis exhausts RAM and never returns — the build hangs rather than
         * failing, which is much worse than a slower report.
         *
         * This disables only the *release-blocking gate*. Lint still runs on demand:
         * `./gradlew :app:lint` reports the same issues without blocking anything, and
         * `abortOnError` is untouched for that path.
         *
         * Re-enable before shipping, once the release build runs on hardware that can afford it.
         */
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // The extension test-support code references androidTest-only classes.
                // Robolectric only runs the unit-test variants, so keep it out of their
                // classpath and let ExtensionTestBase stay in androidTest where it belongs.
                it.testLogging {
                    events("failed")
                    exceptionFormat =
                        org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                }
            }
        }
    }

    // Extension tests load the :mock-extension APK, so that module must be built first.
    tasks.withType<Test>().configureEach {
        dependsOn(":mock-extension:assembleDebug")
    }

    // Copy the extension APK into the androidTest asset dir before it is packaged.
    val mockExtensionApk = tasks.register<Sync>("copyMockExtensionApk") {
        // `Sync` (not `Copy`) so the destination is emptied first: a renamed or removed dex
        // entry must not survive from a previous build and be packed into the test APK.
        from(project(":mock-extension").layout.buildDirectory.dir("outputs/apk/debug")) {
            include("mock-extension-debug.apk")
            rename { "mock-extension.apk" }
        }
        // A real third-party extension, signed by a key we do not control, so the signature
        // tests run against an actual APK Signature Scheme block rather than a fixture.
        from(layout.projectDirectory.dir("src/androidTest/fixtures")) {
            include("real-mangasource-extension.apk")
            include("real-keiyoushi-manga-extension.apk")
            include("real-anime-catalog-extension.apk")
        }
        into(layout.buildDirectory.dir("androidTestAssets"))
        dependsOn(":mock-extension:assembleDebug")
    }

    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("AndroidTestAssets") }
        .configureEach { dependsOn(mockExtensionApk) }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }
}

// Emit Room schemas for migration diffing / auto-migration review.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    // The Aniyomi compatibility layer. Extensions are compiled against these exact classes.
    implementation(project(":source-api"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Room — the single source of truth for the UI.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.palette)
    implementation(libs.coil.compose)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)

    // BOM keeps the supabase-kt modules on the same version.
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.kt)
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)

    // supabase-kt pulls androidx.browser transitively (Custom Tabs for OAuth), and the
    // newest release demands compileSdk 36 / AGP 8.9+. 1.8.0 is API-compatible for our
    // use and keeps the toolchain on AGP 8.7 / compileSdk 35.
    constraints {
        implementation(libs.androidx.browser) {
            version { strictly(libs.versions.browser.get()) }
        }
    }

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    // Room DAO tests run on the JVM under Robolectric, so `./gradlew test` verifies the
    // data layer without an emulator.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.testext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Lets the Kitsu client be tested against real HTTP on the JVM: request construction and
    // response handling are where that API fails, and neither is reachable without a server.
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.testrunner)

    // Extension tests DexClassLoader the real :mock-extension APK, so its dex is shipped as
    // a test asset and copied into the app sandbox at runtime (the host filesystem is not
    // reachable from the app's sandbox on an emulator).
    android {
        sourceSets {
            getByName("androidTest") {
                assets.srcDir(layout.buildDirectory.dir("androidTestAssets"))
                // Exported Room schemas, as assets. `MigrationTestHelper` reads the historical
                // schema JSONs out of the test APK's assets to build a real v2 database before
                // migrating it; without these it cannot know what v2 looked like and every
                // migration test fails with "Cannot find the schema file".
                assets.srcDir("$projectDir/schemas")
            }
        }
    }
    androidTestImplementation(libs.androidx.testext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}