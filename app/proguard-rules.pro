# kotlinx.serialization: keep generated serializers for the policy / stats / inbox files
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class dev.anchor.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.anchor.**$$serializer { *; }
-keepclasseswithmembers class dev.anchor.** { kotlinx.serialization.KSerializer serializer(...); }
