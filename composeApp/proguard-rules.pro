# kotlinx.serialization: keep generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class se.pusslet.**$$serializer { *; }
-keepclassmembers class se.pusslet.** { *** Companion; }
-keepclasseswithmembers class se.pusslet.** { kotlinx.serialization.KSerializer serializer(...); }

# Room (generated _Impl classes are referenced reflectively by the constructor object)
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Ktor / OkHttp optional dependencies
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn io.ktor.util.debug.**
