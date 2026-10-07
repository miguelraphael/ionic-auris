pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Auris"
// The Aniyomi extension compatibility layer lives in its own module so that both the app
// and the mock extension compile against the *same* API classes — which is exactly what
// happens at runtime, since a DexClassLoader-loaded extension resolves them from the host.
include(":app")
include(":source-api")
include(":mock-extension")
// Compile-only guard: asserts :source-api matches the Anikku API extensions are built
// against. Produces no artifact; a red build means real extensions would not link.
include(":source-api-conformance")