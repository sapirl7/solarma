# Add project specific ProGuard rules here.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep Solana/Wallet related classes
-keep class com.solana.** { *; }
-keep class com.solanamobile.** { *; }
