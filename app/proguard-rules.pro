# Keep serialization models
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class io.github.guellenmade.rootlessvm.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.guellenmade.rootlessvm.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Commons Compress uses reflection for tar/pax handling
-dontnote org.apache.commons.compress.**
-keep class org.apache.commons.compress.** { *; }
-keep class org.tukaani.xz.** { *; }
