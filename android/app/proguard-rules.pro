# --- kotlinx.serialization:协议消息模型 ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.blenderar.client.**$$serializer { *; }
-keepclassmembers class com.blenderar.client.** {
    *** Companion;
}
-keepclasseswithmembers class com.blenderar.client.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Filament / gltfio JNI ---
-keep class com.google.android.filament.** { *; }
-keep class com.google.android.filament.gltfio.** { *; }

# --- ARCore ---
-keep class com.google.ar.core.** { *; }

# --- OkHttp ---
-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
