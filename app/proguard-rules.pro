# Preserve exact line numbers & annotations for Google Play Console Crash/ANR Deobfuscation
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*

# Keep app models & public entry points safe while R8 optimizes & generates Play Store proguard.map
-keep class com.friendbook.social.models.** { *; }
-keep public class com.friendbook.social.** extends android.app.Activity
-keep public class com.friendbook.social.** extends android.app.Fragment
-keep public class com.friendbook.social.** extends android.app.Application

# Firebase & Google Play Services
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# Direct Google Sign-In (AndroidX Credentials & Google Identity)
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
-dontwarn androidx.credentials.**
-dontwarn com.google.android.libraries.identity.googleid.**

# Glide image loader
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep class com.bumptech.glide.** { *; }
-dontwarn com.bumptech.glide.**

# Google AdMob Native Advanced & UMP GDPR Consent SDK
-keep class com.google.android.gms.ads.** { *; }
-keep class com.google.android.ump.** { *; }
-dontwarn com.google.android.gms.ads.**
-dontwarn com.google.android.ump.**
