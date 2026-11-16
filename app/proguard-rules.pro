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

# Suppress benign optional library warnings while allowing R8 to obfuscate & shrink all unused library code
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**
-dontwarn androidx.credentials.**
-dontwarn com.google.android.libraries.identity.googleid.**
-dontwarn com.bumptech.glide.**
-dontwarn com.google.android.ump.**
-dontwarn okhttp3.**
-dontwarn okio.**

# ---------------------------------------------------------------------------
# Meta Audience Network + AdMob mediation adapter.
# Added in 2.8.90; the Meta SDK is reflection-heavy (its own consumer rules do not
# cover every entry point here) and combined with `-repackageclasses 'o'` + R8 full
# mode it failed ':app:minifyReleaseWithR8' with "Compilation failed to complete".
# Keep its entry points and stop R8 warning about its optional dependencies.
# ---------------------------------------------------------------------------
-keep class com.facebook.** { *; }
-keep interface com.facebook.** { *; }
-keep class com.facebook.ads.** { *; }
-keep class com.facebook.ads.internal.** { *; }
-keep class com.google.ads.mediation.facebook.** { *; }
-keep class com.google.android.gms.ads.mediation.** { *; }
-keep class * implements com.google.android.gms.ads.mediation.MediationAdapter { *; }
-keep class * extends com.google.android.gms.ads.mediation.admob.AdMobExtras { *; }

# Meta SDK pulls in optional Play/AppLovin/etc. classes it never uses here.
-dontwarn com.facebook.**
-dontwarn com.google.ads.mediation.**
-dontwarn com.applovin.**
-dontwarn com.unity3d.**
-dontwarn com.ironsource.**
-dontwarn com.mbridge.**
-dontwarn com.vungle.**
-dontwarn com.pangle.**
-dontwarn com.bytedance.sdk.openadsdk.**
-dontwarn com.fyber.**
-dontwarn com.smaato.**
-dontwarn com.inmobi.**

# Enum/annotation metadata the SDKs read reflectively.
-keepattributes AnnotationDefault,MethodParameters,Exceptions

# Keep the Meta application-id bootstrap referenced from AndroidManifest.
-keep class com.facebook.FacebookSdk { *; }
-keep class com.facebook.appevents.AppEventsLogger { *; }
