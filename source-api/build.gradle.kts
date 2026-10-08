plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "eu.kanade.tachiyomi.sourceapi"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Extensions are compiled against OkHttp, Jsoup and RxJava 1.x; these must stay
    // binary-compatible with what an extension was built against. OkHttp 4 -> 5 in
    // particular is not a drop-in change.
    api(libs.okhttp)
    api(libs.jsoup)
    api(libs.rxjava)
    implementation(libs.androidx.core.ktx)
    // ConfigurableAnimeSource.setupPreferenceScreen(PreferenceScreen) is part of the extension
    // ABI — the parameter type appears in the method descriptor, so a real extension cannot
    // link without this on the host classpath.
    api(libs.androidx.preference)
    // Aniyomi's source-api ships Injekt, and real extensions call into it
    // (`uy.kohesive.injekt.InjektKt`) when building their configuration. Without it on the host
    // classpath the extension dex fails to link with ClassNotFoundException, which is the next
    // blocker after the three model types.
    api(libs.injekt.core)
    // SAnime.memo is a JsonObject, matching Aniyomi's model.
    api(libs.kotlinx.serialization.json)
}