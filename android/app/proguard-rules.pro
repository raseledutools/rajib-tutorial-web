# WebView app — proguard rules minimal
-keepattributes *Annotation*
-keep class com.rajibtutorial.** { *; }

## Rules for NewPipeExtractor
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
