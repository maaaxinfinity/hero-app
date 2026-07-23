import java.util.Properties
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

// The google-services plugin materializes Firebase config from
// google-services.json (FCM). That file is a credential and is gitignored, so a
// public checkout won't have it — apply the plugin only when it's present. FCM
// then stays inert at runtime (RemotePush falls back to UnifiedPush) rather than
// failing the build. firebase-messaging is still linked (see androidMain) so the
// service compiles regardless.
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.googleServices.get().pluginId)
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions { jvmTarget = "17" }
        }
    }
    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.ktor.client.okhttp)
                // UnifiedPush: self-hosted push transport (no Google dependency).
                implementation(libs.unifiedpush.connector)
                // FCM: the optional second transport. Linked so HeroFirebaseService
                // compiles; inert at runtime unless google-services.json is present
                // (then RemotePush prefers FCM, else falls back to UnifiedPush).
                implementation(libs.firebase.messaging)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.ktor.client.okhttp)
            }
        }
    }
}

// --- Android release signing --------------------------------------------------
// Signing secrets never live in the repo. Locally, put them in a gitignored
// keystore.properties at the project root (storeFile defaults to
// composeApp/keystore.jks); in CI, release.yml materializes the keystore +
// passwords from GitHub secrets into env. A RELEASE build MUST be signed with the
// real release keystore: a debug-signed release has a different certificate that
// Android refuses to upgrade over a real install, so it is never publishable.
// When no keystore is configured the release build FAILS CLOSED (see the
// taskGraph guard after the android block). The debug fallback is offered only
// for a local build explicitly opted into with -PallowDebugSigningForRelease —
// a flag CI never passes, so a missing/misconfigured secret can't silently ship
// a debug-signed APK.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun releaseSecret(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey) ?: System.getenv(envKey)
val releaseStoreFile = file(releaseSecret("storeFile", "KEYSTORE_FILE") ?: "keystore.jks")
val hasReleaseKeystore = releaseStoreFile.exists()
val allowDebugSigningForRelease = hasProperty("allowDebugSigningForRelease")

android {
    namespace = "io.hero.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "io.hero.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 23
        versionName = "0.5.17"
    }
    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = releaseStoreFile
                storePassword = releaseSecret("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = releaseSecret("keyAlias", "KEY_ALIAS")
                keyPassword = releaseSecret("keyPassword", "KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // With a real keystore, sign for real. Without one, keep configuration
            // valid (so assembleDebug and unit tests still evaluate on an
            // unconfigured checkout) by pointing at the debug config — but the
            // taskGraph guard below turns an *actual* release assembly into a hard
            // failure unless -PallowDebugSigningForRelease was explicitly given.
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                if (allowDebugSigningForRelease) {
                    logger.warn("allowDebugSigningForRelease is set — signing the RELEASE build with the DEBUG key; this artifact is NOT publishable")
                }
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Fail closed: a release APK must never be debug-signed. Configuration above
// stays valid on an unconfigured checkout (so assembleDebug and unit tests run),
// but scheduling an actual release-packaging task without a real keystore aborts
// the build here. A contributor who deliberately wants a local debug-signed
// release opts in with -PallowDebugSigningForRelease; CI never passes that flag,
// so a release job with a missing/misconfigured keystore fails instead of
// publishing an unupgradable debug-signed APK.
if (!hasReleaseKeystore && !allowDebugSigningForRelease) {
    gradle.taskGraph.whenReady {
        val releaseTask = gradle.taskGraph.allTasks.firstOrNull { t ->
            t.project == project && (t.name == "assembleRelease" || t.name == "packageRelease")
        }
        if (releaseTask != null) {
            throw GradleException(
                "Refusing to build ${releaseTask.path}: no release keystore is configured, so the APK " +
                    "would be signed with the debug key and could never upgrade a real release install. " +
                    "Configure keystore.properties or the KEYSTORE_* env (see .github/workflows/release.yml), " +
                    "or pass -PallowDebugSigningForRelease to build a local debug-signed APK on purpose.",
            )
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.hero.app.MainKt"
        // OkHttp pulls in optional TLS providers / Android classes the desktop JVM
        // lacks; without these -dontwarn rules the release ProGuard pass fails.
        buildTypes.release.proguard {
            configurationFiles.from(project.file("proguard-desktop.pro"))
        }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "HERO"
            packageVersion = "1.0.0"
        }
    }
}
