# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in Android SDK's proguard-android-optimize.txt.

# Kotlinx Serialization
-keepattributes *Annotation*,InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep @Serializable data models (User, UserKey, BackupPayload, EncryptedBackupEnvelope) and generated serializers
-keep,includedescriptorclasses class com.nairusoft.bbre.model.**$$serializer { *; }
-keepclassmembers class com.nairusoft.bbre.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.nairusoft.bbre.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.nairusoft.bbre.security.**$$serializer { *; }
-keepclassmembers class com.nairusoft.bbre.security.** {
    *** Companion;
}
-keepclasseswithmembers class com.nairusoft.bbre.security.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class com.nairusoft.bbre.** { *; }
