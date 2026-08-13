# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep Picovoice Porcupine classes for wake-word detection
-keep class ai.picovoice.porcupine.** { *; }
-dontwarn ai.picovoice.porcupine.**

# XXPermissions (com.hjq.permissions) reflects into hidden Android permission
# APIs and OEM-specific permission activities by class/method name. Without
# these rules R8 renames those internals in release builds, which crashes
# every screen that requests permissions on open (e.g. the "Find IMI Glasses"
# scan page, which calls requestPermissions() from onResume) — this does not
# reproduce in debug because minification is off there.
-keep class com.hjq.permissions.** { *; }
-keepclassmembers class com.hjq.permissions.** { *; }
-dontwarn com.hjq.permissions.**

# Keep EventBus classes
-keepattributes *Annotation*
-keepclassmembers class ** {
    @org.greenrobot.eventbus.Subscribe <methods>;
}
-keep enum org.greenrobot.eventbus.ThreadMode { *; }

# Oudmon/Smart Glasses SDK ke liye rules - keep reflection targets
-keep class com.oudmon.ble.** { *; }
-keepclassmembers class com.oudmon.ble.** { *; }

# Specifically keep the class that the SDK reflects into
-keep class com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp { *; }

# JTransforms (JTransforms/JLargeArrays) references JDK-internal sun.misc.Cleaner, not present on Android
-dontwarn sun.misc.Cleaner

# ONNX Runtime's native (C++/JNI) side constructs NodeInfo/ValueInfo and other
# model-metadata classes by calling their Java constructors with a fixed
# signature looked up at runtime. R8 was renaming/altering those constructors
# in release builds (no keep rule existed), so the native call no longer
# matched and the process aborted with SIGABRT as soon as the wake-word model
# loaded — java.lang.NoSuchMethodError on ai.onnxruntime.NodeInfo.<init>.
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**