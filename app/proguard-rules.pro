# Keep metadata required by Hilt, Room, Compose and serialization while
# allowing R8 to aggressively rename and repackage the rest of the release.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-allowaccessmodification
-adaptclassstrings
-repackageclasses 'p'
-renamesourcefileattribute SourceFile

# Navigation keys are serialized across process recreation.
-keep,allowoptimization,allowobfuscation class ** implements androidx.navigation3.runtime.NavKey {
    <fields>;
}

# Optional providers which are not bundled on Android.
-dontwarn javax.el.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
