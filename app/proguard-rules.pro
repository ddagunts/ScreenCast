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
