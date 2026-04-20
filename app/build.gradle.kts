import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
        versionCode = 1
        versionName = "0.1"
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
            isMinifyEnabled = false
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/INDEX.LIST",
            "META-INF/io.netty.versions.properties",
            "META-INF/*.kotlin_module",
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("io.ktor:ktor-server-core:2.3.13")
    implementation("io.ktor:ktor-server-cio:2.3.13")
    implementation("io.ktor:ktor-server-auto-head-response:2.3.13")
    implementation("io.ktor:ktor-server-cors:2.3.13")
    implementation("org.slf4j:slf4j-android:1.7.36")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
