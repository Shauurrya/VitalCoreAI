# VitalCore AI — R8 / ProGuard rules
#
# `app/build.gradle.kts` has referenced this file since the project was created, but the file
# did not exist. That is harmless only while `isMinifyEnabled = false`; the first release
# build that turns minification on would have failed on a missing file, or — worse — would
# have succeeded and stripped something it should not have.
#
# Nothing here is speculative. Every rule below exists because the library it names does
# reflection that R8 cannot see.

# ── Room ────────────────────────────────────────────────────────────────────
# Entities are constructed reflectively from cursor rows, and the generated _Impl classes
# are looked up by name.
-keep class com.example.vitalcoreai.data.db.entity.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# ── Hilt / Dagger ───────────────────────────────────────────────────────────
# Generated components and the @Inject constructors they call.
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper
-keepclasseswithmembers class * { @javax.inject.Inject <init>(...); }

# ── WorkManager ─────────────────────────────────────────────────────────────
# Workers are instantiated by class name from the WorkManager database, so a renamed class
# means a scheduled job that can never run again — including jobs enqueued before the update.
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class com.example.vitalcoreai.data.sync.** { *; }

# ── Health Connect ──────────────────────────────────────────────────────────
# Record types are resolved by KClass at the permission and query boundary.
-keep class androidx.health.connect.client.records.** { *; }
-keep class androidx.health.connect.client.permission.** { *; }

# ── Kotlin ──────────────────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keepclassmembers class ** { @kotlin.jvm.JvmStatic *; }

# ── The analytics layer ─────────────────────────────────────────────────────
# Enum NAMES are persisted as TEXT in computed_scores (RobustStats.Trend, AcwrZone,
# Confidence, RecoveryStatus, …) and read back with valueOf(). Obfuscating them would not
# break the build or the tests — it would silently invalidate every historical row, because
# the strings already in the database would no longer match any constant.
-keepclassmembers enum com.example.vitalcoreai.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public static ** entries();
}

# Same reasoning for the score-factor encoding: field names are not serialised, but the
# enum-name round-trip above is, and these objects are the ones that produce it.
-keep class com.example.vitalcoreai.analytics.ScoreFactor { *; }
-keep class com.example.vitalcoreai.analytics.ScorePipeline$* { *; }

# ── Compose ─────────────────────────────────────────────────────────────────
-dontwarn androidx.compose.**

# Keep line numbers so a crash report from a release build is readable, while still
# obfuscating the file name itself.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
