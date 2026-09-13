# This is a configuration file for R8

-verbose
-allowaccessmodification
-repackageclasses

# Note that you cannot just include these flags in your own
# configuration file; if you are including this file, optimization
# will be turned off. You'll need to either edit this file, or
# duplicate the contents of this file and remove the include of this
# file from your project's proguard.config path property.

# For native methods, see http://proguard.sourceforge.net/manual/examples.html#native
-keepclasseswithmembernames class * {
    native <methods>;
}

# We only need to keep ComposeView
-keep public class androidx.compose.ui.platform.ComposeView {
    public <init>(android.content.Context, android.util.AttributeSet);
}

# For enumeration classes
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}

# AndroidX + support library contains references to newer platform versions.
# Don't warn about those in case this app is linking against an older
# platform version.  We know about them, and they are safe.
-dontwarn android.support.**
-dontwarn androidx.**

-keepattributes SourceFile,
                LineNumberTable,
                RuntimeVisibleAnnotations,
                RuntimeVisibleParameterAnnotations,
                RuntimeVisibleTypeAnnotations,
                AnnotationDefault

-renamesourcefileattribute SourceFile

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# OkHttp platform detection uses reflection to find these
-keep class org.conscrypt.Conscrypt { *; }
-keep class org.conscrypt.ConscryptHostnameVerifier { *; }
-keep class org.bouncycastle.jsse.** { *; }
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.openjsse.** { *; }

# Dagger
-dontwarn com.google.errorprone.annotations.*

# Retain the generic signature of retrofit2.Call until added to Retrofit.
# Issue: https://github.com/square/retrofit/issues/3580.
# Pull request: https://github.com/square/retrofit/pull/3579.
-keep,allowobfuscation,allowshrinking class retrofit2.Call

# See https://issuetracker.google.com/issues/265188224
-keep,allowshrinking class * extends androidx.compose.ui.node.ModifierNodeElement {}

# Terminal JNI — accessed via reflection, R8 must not strip
-keep class com.termux.terminal.JNI { *; }
-keep class com.termux.terminal.** { *; }

# jtokkit - loads BPE vocabularies from classpath resources
-keep class com.knuddels.jtokkit.** { *; }
-keepclassmembers class com.knuddels.jtokkit.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# MapLibre
-keep class org.maplibre.** { *; }
-keep class com.mapbox.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# SSHJ / BouncyCastle
-dontwarn sun.security.x509.X509Key
-dontwarn org.ietf.jgss.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class net.schmizz.sshj.** { *; }
-keep class net.schmizz.sshj.userauth.keyprovider.** { *; }
-keep class net.schmizz.sshj.common.** { *; }
-keep class net.schmizz.sshj.transport.** { *; }
-dontwarn net.schmizz.sshj.**
-dontwarn net.i2p.crypto.**

# PDFBox - optional JPEG2000 decoder not bundled
-dontwarn com.gemalto.jp2.**

# ONNX Runtime - JNI bindings loaded via native methods and reflection
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# DJL HuggingFace tokenizer - resolves engine + native libs via reflection and
# ServiceLoader (META-INF/services). R8 renaming/stripping breaks tokenizer init,
# which silently disables on-device embeddings.
-keep class ai.djl.** { *; }
-keepclassmembers class ai.djl.** { *; }
-keep class ai.djl.huggingface.tokenizers.** { *; }
-keep class ai.djl.huggingface.tokenizers.jni.** { *; }
-keepnames class ai.djl.** { *; }
-dontwarn ai.djl.**
# Keep ServiceLoader provider registrations used by DJL engines
-keep class * implements ai.djl.engine.EngineProvider { *; }
# DJL transitively references commons-compress, which optionally references
# commons-lang3 SystemProperties (not bundled). Safe to ignore.
-dontwarn org.apache.commons.compress.**
-dontwarn org.apache.commons.lang3.**
-dontwarn com.google.gson.**