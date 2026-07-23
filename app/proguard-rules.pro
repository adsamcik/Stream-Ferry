# Jellyfin Bridge R8 / ProGuard keep rules.
# Keep documentation: minification + resource shrinking are enabled for release (app/build.gradle.kts).

# --- Cast SDK: OptionsProvider is loaded reflectively from manifest meta-data. ---
-keep class com.videobridge.data.cast.CastOptionsProvider { *; }
-keep class com.google.android.gms.cast.framework.** { *; }

# --- kotlinx.serialization: keep generated serializers. ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.videobridge.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Jellyfin SDK models are (de)serialized; keep their members. ---
-keep class org.jellyfin.sdk.model.** { *; }

# --- OkHttp / Okio (publishes its own consumer rules; these are belt-and-suspenders). ---
-dontwarn okhttp3.**
-dontwarn okio.**

# Do not strip line numbers needed for redacted crash triage; keep source file attribute hidden.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
