# The extension loader discovers sources by reflection, so the API classes must keep their
# names, members and (for implementations) their no-arg constructors.
-keep class eu.kanade.tachiyomi.source.** { *; }
-keep class eu.kanade.tachiyomi.animesource.** { *; }
-keep class eu.kanade.tachiyomi.network.** { *; }
-keep class eu.kanade.tachiyomi.util.** { *; }

# Extensions reference these; R8 must not strip or rename their signatures.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jsoup.**
-dontwarn org.jetbrains.annotations.**