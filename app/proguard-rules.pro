# Use this file to provide custom ProGuard rules for your project.
# By default, the rules in this file are appended to the default ProGuard
# rules configuration file specified in build.gradle.kts.
# Fix for Tink library missing error-prone annotations
-dontwarn com.google.errorprone.annotations.**

# Disable obfuscation to keep names readable and avoid serialization issues
-dontobfuscate

# GSON requirements
...
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.stream.** { *; }

# Prevent GSON from removing the generic signatures of TypeToken
# This is critical for AccountManager.kt: getAccounts()
-keep class * extends com.google.gson.reflect.TypeToken
-keepnames class * extends com.google.gson.reflect.TypeToken
-keepclassmembers class * extends com.google.gson.reflect.TypeToken { *; }

# Keep GSON models
-keepclassmembers class com.theundefined.omnis.data.model.** { *; }
-keep class com.theundefined.omnis.data.model.** { *; }

# Retrofit & OkHttp
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Keep generic types of maps and lists which GSON needs
-keep class java.util.Map { *; }
-keep class java.util.List { *; }
-keep class java.util.Set { *; }
-keep class retrofit2.Response { *; }

# General Keep for all data classes just in case
-keep @com.google.gson.annotations.SerializedName class * {
    <fields>;
}
