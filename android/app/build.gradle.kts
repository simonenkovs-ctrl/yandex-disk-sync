plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing is configured via local.properties (git-ignored):
//   keystore.storePassword=...
//   keystore.keyAlias=...
//   keystore.keyPassword=...
// In CI the same properties are written from GitHub Actions secrets.
val keystoreFile = file("release.keystore")
val keystoreStorePassword = findProperty("keystore.storePassword") as String?
val keystoreKeyAlias = findProperty("keystore.keyAlias") as String?
val keystoreKeyPassword = findProperty("keystore.keyPassword") as String?
val releaseSigningAvailable =
    keystoreFile.exists() &&
        !keystoreStorePassword.isNullOrBlank() &&
        !keystoreKeyAlias.isNullOrBlank() &&
        !keystoreKeyPassword.isNullOrBlank()

if (!releaseSigningAvailable) {
    logger.warn(
        "Release signing is not configured: 'release.keystore' is missing or " +
            "keystore.storePassword / keystore.keyAlias / keystore.keyPassword are not set in local.properties. " +
            "Release APKs cannot be built until it is provided. Debug builds fall back to the default debug key."
    )
}

android {
    namespace = "ru.simonenkov.yandexdisksync"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.simonenkov.yandexdisksync"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "1.3.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystoreStorePassword
                keyAlias = keystoreKeyAlias
                keyPassword = keystoreKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        val releaseSigning = signingConfigs.findByName("release")
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigning != null) {
                signingConfig = releaseSigning
            }
        }
        debug {
            if (releaseSigning != null) {
                signingConfig = releaseSigning
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
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.ui.tooling)
}




