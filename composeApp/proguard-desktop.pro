# Desktop uber-jar ProGuard rules.
#
# OkHttp (the Ktor client engine) references optional TLS providers (Conscrypt,
# BouncyCastle, OpenJSSE) and Android platform classes reflectively — it uses
# whichever is present at runtime and ignores the rest. On the desktop JVM none
# of these are on the classpath, so ProGuard emits "can't find referenced class"
# warnings and, treating warnings as errors, fails the build. Silence them; the
# code paths are never taken on desktop (the JVM's default TLS is used).
-dontwarn okhttp3.**
-dontwarn okio.**

# The engine is constructed explicitly (heroHttpClient actuals), but the uber
# jar still carries META-INF/services entries pointing at the OkHttp engine —
# keep the whole transport so ServiceLoader discovery can never resolve to a
# stripped class again (v0.5.7 crashed exactly that way).
-keep class io.ktor.client.engine.okhttp.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
# Same ServiceLoader trap, different service: ContentNegotiation's json()
# discovers KotlinxSerializationExtensionProvider implementations; stripping
# the provider behind its surviving services entry crashes Api's constructor.
-keep class io.ktor.serialization.kotlinx.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn android.**
-dontwarn javax.annotation.**
