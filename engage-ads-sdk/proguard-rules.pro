# Keep SimpleXML annotations
-keep class com.engage.engageadssdk.** { *; }
# Keep all classes and members of Amazon MediaPlayer
-keep class com.amazon.mediaplayer.** { *; }
# Preserve all public methods and fields of Amazon MediaPlayer and ExoPlayer
-keepclassmembers class com.amazon.mediaplayer.** {
    public *;
}
-dontwarn java.lang.System
-keep class java.lang.System { *; }
-keepclassmembers class java.lang.System {
    public *;
}
