# Media3 / Compose ship consumer rules. Add app-specific keeps here.

# Keep exception class names readable in error messages shown to the user.
-keepnames class * extends java.lang.Throwable

# ML Kit face detection (bundled model). The native libface_detector_v2_jni.so reaches Java
# classes through JNI and the component registrars are created by reflection, so R8 full mode
# must not strip, rename or inline them. usage.txt of the 6e3898c build showed whole
# com.google.mlkit.common.* and mlkit_vision_face_bundled classes removed.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.gms.vision.** { *; }
-keep class com.google.android.odml.image.** { *; }
-keep class com.google.android.gms.common.internal.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn com.google.mlkit.**
