# Ktor 3 + CIO: the engine is looked up reflectively via ServiceLoader /
# engine provider classes, and server plugins (CORS, AutoHeadResponse) use
# reflection-ish discovery. Keep the surface we actually depend on and
# silence warnings about server-side packages we don't ship.
-keep class io.ktor.server.engine.EngineFactory { *; }
-keep class io.ktor.server.cio.** { *; }
-keep class io.ktor.server.plugins.cors.** { *; }
-keep class io.ktor.server.plugins.autohead.** { *; }
-dontwarn io.ktor.**
-dontwarn io.netty.**
-dontwarn org.slf4j.**

# kotlinx.coroutines touches a few internal volatile fields reflectively;
# the default Android rules cover this but some rule sets miss it.
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.debug.**

# AGP's default-proguard-android-optimize.txt already keeps Compose + AndroidX
# runtime classes; nothing extra needed there.

# libwebrtc: JNI_OnLoad calls back into the JVM to resolve Java classes by
# exact name (FindClass / GetMethodID). R8 minification renames those
# classes and the native side crashes inside JNI_OnLoad before
# PeerConnectionFactory.initialize() can return — release APK prior to this
# rule crashed on any WebRTC cast start. Keep every symbol in org.webrtc.*
# so the native lookups match what the Java layer actually exposes.
-keep class org.webrtc.** { *; }
-keep interface org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Our AudioBufferCallback implementation is invoked from libwebrtc's native
# audio thread via JNI. R8 can see the interface is kept but has no visible
# Java call site for our onBuffer override and would otherwise strip or
# rename it. Pin any implementer's members to be safe.
-keepclassmembers class * implements org.webrtc.audio.JavaAudioDeviceModule$AudioBufferCallback {
    *;
}
-keepclassmembers class * implements org.webrtc.audio.JavaAudioDeviceModule$SamplesReadyCallback {
    *;
}

# androidx.security.crypto pulls in Tink, which references Error Prone /
# javax.annotation compile-time-only annotations that aren't on the runtime
# classpath. R8 fails the build with "Missing class" without these
# `-dontwarn` lines. The annotations have no runtime semantics; nothing is
# lost by dropping them.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**
