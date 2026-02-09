# Add project specific ProGuard rules here.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Solana / MWA ───────────────────────────────────────
-keep class com.solana.** { *; }
-keep class com.solanamobile.** { *; }

# ── Hilt / Dagger ──────────────────────────────────────
-dontwarn dagger.hilt.**
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# ── Kotlin Coroutines ──────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ── Room Database ──────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# ── Compose ────────────────────────────────────────────
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# ── Data classes used in serialization ─────────────────
-keep class app.solarma.data.** { *; }
-keep class app.solarma.wallet.** { *; }
