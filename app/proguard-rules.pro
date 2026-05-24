# Use this file to provide custom ProGuard rules for your project.
# By default, the rules in this file are appended to the default ProGuard
# rules configuration file specified in build.gradle.kts.

# Fix for Tink library missing error-prone annotations
-dontwarn com.google.errorprone.annotations.**

# Keep GSON models
-keepclassmembers class com.theundefined.omnis.data.model.** { *; }
-keep class com.theundefined.omnis.data.model.** { *; }

# GSON requirements
-keepattributes Signature
-keepattributes *Annotation*
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.stream.** { *; }

# Retrofit & OkHttp
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
