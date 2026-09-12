plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.noblebits.overkey"
    compileSdk = 36

    defaultConfig {
        minSdk = 30 // WindowMetrics and WindowInsets.Type are API 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
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
            signingConfig = signingConfigs.getByName("debug")
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
