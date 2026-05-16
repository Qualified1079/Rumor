# Rumor ProGuard rules

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class **$$serializer { CREATOR <fields>; }
-keep,includedescriptorclasses class com.rumor.mesh.**$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep BouncyCastle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep Room entities
-keep class com.rumor.mesh.data.** { *; }

# Keep Koin — class names are resolved by string at runtime via KClass.qualifiedName
-keep class org.koin.** { *; }
-keepnames class * { *; }
-keepclassmembers class * {
    @org.koin.core.annotation.* <methods>;
}

# Keep model classes used in serialization
-keep class com.rumor.mesh.core.model.** { *; }
