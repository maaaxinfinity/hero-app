import java.util.Properties
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
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
        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.ktor.client.cio)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.ktor.client.cio)
            }
        }
    }
}

android {
    namespace = "io.hero.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "io.hero.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0"
    }
    // Signing secrets never live in the repo. Locally, put them in a gitignored
    // keystore.properties at the project root; in CI, provide them via env
    // (release.yml materializes the keystore + passwords from GitHub secrets).
    // When nothing is configured, release falls back to debug signing so an
    // unconfigured checkout still builds.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun secret(propKey: String, envKey: String): String? =
        keystoreProps.getProperty(propKey) ?: System.getenv(envKey)

    signingConfigs {
        create("release") {
            val storePath = secret("storeFile", "KEYSTORE_FILE") ?: "keystore.jks"
            val sf = file(storePath)
            if (sf.exists()) {
                storeFile = sf
                storePassword = secret("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = secret("keyAlias", "KEY_ALIAS")
                keyPassword = secret("keyPassword", "KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile?.exists() == true) {
                releaseSigning
            } else {
                logger.warn("no release keystore configured; signing the release build with the debug key")
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.desktop {
    application {
        mainClass = "io.hero.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "HERO"
            packageVersion = "1.0.0"
        }
    }
}
