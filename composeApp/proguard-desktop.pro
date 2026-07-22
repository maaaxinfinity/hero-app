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
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn android.**
-dontwarn javax.annotation.**
