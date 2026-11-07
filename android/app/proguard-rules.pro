# JNI uses fixed Java class/method names; integration checks also inspect our UI state.
# Keep the small application bridge, while shrinking unused AndroidX/Material code.
-keep class org.luma.music.** { *; }
# The separately built instrumentation APK shares the target's Kotlin runtime.
# Preserve runtime names/members; unused Material/AndroidX code is still shrunk.
-keep class kotlin.** { *; }
