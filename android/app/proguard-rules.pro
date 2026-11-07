# JNI uses fixed Java class/method names; integration checks also inspect our UI state.
# Keep the small application bridge, while shrinking unused AndroidX/Material code.
-keep class org.luma.music.** { *; }
