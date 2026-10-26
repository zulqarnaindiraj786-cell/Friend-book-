# Firebase / Play services keep rules are bundled with the SDKs.
# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep class com.bumptech.glide.** { *; }
-dontwarn com.bumptech.glide.**
# Firestore model reflection (we map manually, but keep names safe)
-keep class com.friendbook.models.** { *; }

# Google AdMob Native Advanced & UMP GDPR Consent SDK keep rules (Release APK safety)
-keep class com.google.android.gms.ads.** { *; }
-keep class com.google.android.ump.** { *; }
-dontwarn com.google.android.gms.ads.**
-dontwarn com.google.android.ump.**

