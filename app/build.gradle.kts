plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.sketchdxf.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sketchdxf.app"
        minSdk = 26
        targetSdk = 36
        // Bump this on every release. Forced updates key off it: BootScreen (see UpdateChecker)
        // blocks anyone whose installed versionCode is below update-config.json's minVersionCode
        // at the repo root — raise minVersionCode there (to this value or lower) to require the
        // update, or leave it as-is/lower to make a release optional.
        versionCode = 8
        versionName = "1.4.3"
        vectorDrawables { useSupportLibrary = true }

        // Real phones are arm; x86/x86_64 ML Kit native libs are emulator-only dead weight.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Play Store upload key — provided by CI via env vars (kept out of git). Falls back to the
    // committed "stable" key for local/debug builds and for CI builds that don't set it, so both
    // `./gradlew assembleDebug` and `./gradlew bundleRelease` always produce a signed artifact.
    val uploadStoreFile = System.getenv("UPLOAD_STORE_FILE")
    val uploadStorePassword = System.getenv("UPLOAD_STORE_PASSWORD")
    val uploadKeyAlias = System.getenv("UPLOAD_KEY_ALIAS")
    val uploadKeyPassword = System.getenv("UPLOAD_KEY_PASSWORD")
    val hasUploadKey = !uploadStoreFile.isNullOrBlank() && !uploadStorePassword.isNullOrBlank()

    // Local "stable" signing key, generated once for this app and committed (same convenience
    // pattern as the POS Billing app) so CI can sign a debug APK + release AAB on every push
    // without needing any secrets configured first.
    val stableStorePassword = "dxf@2026key"
    val stableKeyAlias = "sketchdxf"

    signingConfigs {
        create("stable") {
            storeFile = file("keystore.jks")
            storePassword = stableStorePassword
            keyAlias = stableKeyAlias
            keyPassword = stableStorePassword
        }
        if (hasUploadKey) {
            create("upload") {
                storeFile = file(uploadStoreFile!!)
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            signingConfig = signingConfigs.getByName(if (hasUploadKey) "upload" else "stable")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.8.0")

    // Local offline database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // On-device handwriting recognition — for typing dimensions/labels by hand on the canvas.
    implementation("com.google.mlkit:digital-ink-recognition:19.0.0")

    // On-device English (Latin script) text recognition — for OCR'ing a photographed dimension/
    // label instead of typing it by hand. See OcrTextDialog.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // EXIF rotation, so a sideways photo detects lines right-side-up.
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Play in-app updates: forces an update as soon as one is published (see AppUpdater). Same
    // dependency/approach as the POS Billing and Kerala Lottery apps.
    implementation("com.google.android.play:app-update-ktx:2.1.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
