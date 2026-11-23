# R8 release shrinking and obfuscation; Play computes its own dashboard metric.
-optimizationpasses 5
-overloadaggressively
-repackageclasses 'o'
-allowaccessmodification
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# Glide Generated API / Module entry points (AAR consumer rules handle internal classes)
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep public class * extends com.bumptech.glide.module.AppGlideModule

# Suppress benign optional library warnings while allowing R8 to obfuscate & shrink all unused library code
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**
-dontwarn androidx.credentials.**
-dontwarn com.google.android.libraries.identity.googleid.**
-dontwarn com.bumptech.glide.**
-dontwarn com.google.android.ump.**
-dontwarn okhttp3.**
-dontwarn okio.**

# Meta Audience Network carries compile-time Infer nullability metadata.

# AdMob waterfall loads adapter entry points by their server-supplied class names.
