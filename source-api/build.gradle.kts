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
    // SAnime.memo is a JsonObject, matching Aniyomi's model.
    api(libs.kotlinx.serialization.json)
}