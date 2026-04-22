import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing credentials are read from (in order):
//   1. keystore.properties at the repo root (gitignored, for local releases)
//   2. env vars SCREENCAST_KEYSTORE_FILE / _PASSWORD / _KEY_ALIAS / _KEY_PASSWORD (for CI)
// If none are present, assembleRelease still produces an unsigned APK (not installable).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

val releaseKeystore = signingValue("storeFile", "SCREENCAST_KEYSTORE_FILE")
val releaseStorePassword = signingValue("storePassword", "SCREENCAST_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "SCREENCAST_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "SCREENCAST_KEY_PASSWORD")
val releaseSigningReady =
    releaseKeystore != null && releaseStorePassword != null &&
    releaseKeyAlias != null && releaseKeyPassword != null

android {
    namespace = "io.github.ddagunts.screencast"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ddagunts.screencast"
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = "0.7.0"
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(releaseKeystore!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/INDEX.LIST",
            "META-INF/io.netty.versions.properties",
        )
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    // Icons-extended is ~1 MB uncompressed but R8 strips unused glyphs in
    // release; debug builds carry the full set. We need Pause/Stop/ContentCopy/
    // Cast/VolumeOff which are not in material-icons-core.
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.auto.head.response)
    implementation(libs.ktor.server.cors)

    // WebRTC mode — isolated from the HLS path; only the webrtc/ package
    // references org.webrtc.*. Adds ~18 MB of native code across 4 ABIs; R8
    // cannot strip native libraries, so debug APKs carry the full payload
    // and release APKs still ship every ABI unless the user builds split APKs.
    implementation(libs.webrtc.sdk.android)
    // Ktor uses SLF4J internally. We don't ship a binding: Ktor 3 gracefully
    // falls back to its built-in no-op and Android's logcat carries our own
    // logI/logE from util.LogRepository. Uncomment and add to the catalog if
    // framework logs are needed: org.slf4j:slf4j-jdk14:2.0.x

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.org.json)
}
