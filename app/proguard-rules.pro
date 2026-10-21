# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Aniyomi extension APKs are reflected over; keep the source interfaces intact.
-keep class eu.kanade.tachiyomi.source.** { *; }
-keep class eu.kanade.tachiyomi.source.anime.** { *; }
-keep class eu.kanade.tachiyomi.source.manga.** { *; }
-keep class eu.kanade.tachiyomi.network.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-dontwarn com.google.gson.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.auris.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.auris.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Supabase
-dontwarn org.jetbrains.annotations.**

# NewPipeExtractor runs YouTube's player JavaScript through Rhino and reflects over it, so the
# engine's classes cannot be renamed or stripped. The extractor's own classes are reached through
# its public API only and shrink normally.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.engine.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.schabi.newpipe.extractor.**
