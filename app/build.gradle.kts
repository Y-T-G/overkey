import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/*
 * The real signing key, from keystore.properties beside this file or from the environment on
 * CI. Neither is tracked. Without one the release build falls back to the debug key, so a fresh
 * clone still builds and can be installed; an APK signed that way must never be published,
 * because the debug key is generated per machine and the next build would not install over it.
 * `signedForRelease` is what CI asks before attaching an APK to a GitHub release.
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signing(name: String, env: String): String? =
    keystoreProps.getProperty(name)?.takeIf { it.isNotBlank() } ?: System.getenv(env)?.takeIf { it.isNotBlank() }

// Relative paths are read from the repository root, where the keystore sits beside
// keystore.properties; an absolute one (what CI passes) is taken as it is.
val keyStorePath = signing("storeFile", "OVERKEY_STORE_FILE")
val keyStoreFile = keyStorePath?.let { rootProject.file(it) }
val signedForRelease = keyStoreFile != null && keyStoreFile.exists()

android {
    namespace = "dev.noblebits.overkey"
    compileSdk = 36

    defaultConfig {
        minSdk = 30 // WindowMetrics and WindowInsets.Type are API 30
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
    }

    // AGP otherwise appends an encrypted list of dependencies to the APK signing block. Only Play
    // can read it, and F-Droid refuses an APK that carries it, so reproducible builds need it gone.
    dependenciesInfo {
        includeInApk = false
    }

    signingConfigs {
        if (signedForRelease) {
            create("release") {
                storeFile = keyStoreFile
                storePassword = signing("storePassword", "OVERKEY_STORE_PASSWORD")
                keyAlias = signing("keyAlias", "OVERKEY_KEY_ALIAS")
                // A keystore whose key carries the store's own password needs only three
                // properties, so the fourth is optional.
                keyPassword = signing("keyPassword", "OVERKEY_KEY_PASSWORD")
                    ?: signing("storePassword", "OVERKEY_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            vcsInfo.include = false
        }
    }

    packaging {
        // AGP stores the dex uncompressed from minSdk 28; a 60 KB dex is not worth 30 KB of APK.
        dex.useLegacyPackaging = true
        // Kotlin metadata is only needed by kotlin-reflect, which is not used.
        resources.excludes += listOf("kotlin/**", "META-INF/*.version", "META-INF/*.kotlin_module",
            "kotlin-tooling-metadata.json", "DebugProbesKt.bin")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Every non-null parameter otherwise gets an Intrinsics.checkNotNullParameter call.
        freeCompilerArgs += listOf("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions")
    }
}

dependencies {
    // Shizuku: runs the injector as shell without root. Everything else is framework only.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
