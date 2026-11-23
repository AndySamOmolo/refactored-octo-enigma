# Keep Kotlinx Serialization & Generic Signatures
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, SourceFile, LineNumberTable
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.edgeledger.app.**$$serializer { *; }
-keepclassmembers class com.edgeledger.app.** { *** Companion; }
-keepclasseswithmembers class com.edgeledger.app.** { kotlinx.serialization.KSerializer serializer(...); }

# Keep enums parsed via valueOf(String)
-keepclassmembers enum com.edgeledger.app.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Room entities/DAOs, WorkManager workers, and Glance widget receivers
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class com.edgeledger.app.data.WeeklySyncWorker { *; }
-keep class com.edgeledger.app.widget.TopPicksWidget { *; }
-keep class com.edgeledger.app.widget.TopPicksWidgetReceiver { *; }

-keepnames @kotlinx.serialization.Serializable class com.edgeledger.app.**

# Jetpack Glance ActionCallback reflective instantiation
-keep class * implements androidx.glance.appwidget.action.ActionCallback { public <init>(); }
-keep class com.edgeledger.app.widget.RefreshTopPicksWidgetAction { public <init>(); }

# Coil SVG / AndroidSVG reflection safety under R8 full mode
-keep class coil.decode.SvgDecoder** { *; }
-keep class com.caverock.androidsvg.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
