# JSch loads its crypto implementations reflectively by class name.
-keep class com.jcraft.jsch.** { *; }
# Optional JSch dependencies we don't ship.
-dontwarn org.bouncycastle.**
-dontwarn org.newsclub.**
-dontwarn com.sun.jna.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
