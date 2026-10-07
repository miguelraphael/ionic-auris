plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

// The conformance module must compile on every build: it is the signal that :source-api has
// not drifted from the Anikku API.
tasks.register("checkSourceApiConformance") {
    group = "verification"
    description = "Compiles the :source-api-conformance guard against :source-api."
    dependsOn(":source-api-conformance:compileKotlin")
}
