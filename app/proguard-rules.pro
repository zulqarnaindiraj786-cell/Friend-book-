# Google Play Console DEX Code Optimization & Split Debug Info (R8 Full Mode >75% Obfuscation)
-optimizationpasses 5
-overloadaggressively
-repackageclasses 'o'
-allowaccessmodification
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# Glide Generated API / Module entry points (AAR consumer rules handle internal classes)
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep class com.google.firebase.firestore.** { *; }
-keep class com.google.firebase.database.** { *; }

# Suppress benign optional library warnings while allowing R8 to obfuscate & shrink all unused library code
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**
-dontwarn androidx.credentials.**
-dontwarn com.google.android.libraries.identity.googleid.**
-dontwarn com.bumptech.glide.**
-dontwarn com.google.android.ump.**
-dontwarn okhttp3.**
-dontwarn okio.**
# Meta Audience Network SDK references Facebook infer annotations (compile-time only)
-dontwarn com.facebook.infer.annotation.Nullsafe
-dontwarn com.facebook.infer.annotation.Nullsafe$Mode
