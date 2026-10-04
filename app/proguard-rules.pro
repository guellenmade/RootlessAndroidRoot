# Keep serialization models
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class io.github.guellenmade.rootlessvm.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.guellenmade.rootlessvm.** {
    kotlinx.serialization.KSerializer serializer(...);
}
