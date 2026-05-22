# Use this file to provide custom ProGuard rules for your project.
# By default, the rules in this file are appended to the default ProGuard
# rules configuration file specified in build.gradle.kts.

# Fix for Tink library missing error-prone annotations
-dontwarn com.google.errorprone.annotations.**
