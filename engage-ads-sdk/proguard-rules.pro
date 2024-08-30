# Keep SimpleXML annotations
-keep class com.engage.engageadssdk.** { *; }
-keep class com.engage.engageadssdk.EMAdView { *; }
-keep class com.engage.engageadssdk.EMClientListener { *; }
-keep class com.engage.engageadssdk.EMVideoPlayerListener { *; }
-keep class com.engage.engageadssdk.module.EMAdsModule { *; }
-keep class com.engage.engageadssdk.module.EMAdsModule$Companion { *; }
-keep class com.engage.engageadssdk.module.EMAdsModuleInputBuilder { *; }
-keep class com.engage.engageadssdk.module.EMAdsModuleInput { *; }
-keep class com.engage.engageadssdk.data.EMVASTAd { *; }
# Keep all classes and members of Amazon MediaPlayer
-keep class com.amazon.mediaplayer.** { *; }

# Keep all classes and members of ExoPlayer
-keep class com.google.android.exoplayer2.** { *; }

# Prevent removal or obfuscation of classes and methods used via reflection
-keepclassmembers class * {
    @androidx.annotation.Keep *;
    public protected *;
}

# Preserve all public methods and fields of Amazon MediaPlayer and ExoPlayer
-keepclassmembers class com.amazon.mediaplayer.** {
    public *;
}
-keepclassmembers class com.google.android.exoplayer2.** {
    public *;
}

# Handle system methods and classes
-dontwarn java.lang.System
-keep class java.lang.System { *; }
-keepclassmembers class java.lang.System {
    public *;
}

# Prevent warnings related to missing classes or methods
-dontwarn com.google.android.exoplayer2.**
-dontwarn com.amazon.mediaplayer.**

# Debugging: Disable optimization and shrinking temporarily
 -dontobfuscate
 -dontoptimize
# -dontshrink

