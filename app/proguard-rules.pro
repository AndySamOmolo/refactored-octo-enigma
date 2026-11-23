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
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class com.edgeledger.app.data.WeeklySyncWorker { *; }
-keep class com.edgeledger.app.widget.TopPicksWidget { *; }
-keep class com.edgeledger.app.widget.TopPicksWidgetReceiver { *; }

-keepnames @kotlinx.serialization.Serializable class com.edgeledger.app.**
