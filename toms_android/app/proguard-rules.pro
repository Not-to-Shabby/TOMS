# Release shrinking (R8). Hilt, Room, Retrofit, OkHttp and kotlinx.serialization ship their own consumer rules.

# SQLCipher loads its native library and calls back into these classes by name through JNI.
-keep class net.zetetic.database.** { *; }
-keepclassmembers class net.zetetic.database.** { native <methods>; }

# Keep line numbers so a crash report from the field can be read.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
