import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing key lives outside the repo (see CLAUDE.md). Without it, release builds are unsigned.
val releaseKeyProps = Properties().apply {
    val f = File(System.getProperty("user.home"), ".android-keys/addendum.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "ca.balneaves.addendum"
    compileSdk = 35

    defaultConfig {
        applicationId = "ca.balneaves.addendum"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        resValue("string", "app_name", "Addendum")
    }

    signingConfigs {
        if (releaseKeyProps.isNotEmpty()) {
            create("release") {
                storeFile = file(releaseKeyProps.getProperty("storeFile"))
                storePassword = releaseKeyProps.getProperty("storePassword")
                keyAlias = releaseKeyProps.getProperty("keyAlias")
                keyPassword = releaseKeyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            // Installs alongside the release build instead of replacing it.
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "Addendum (debug)")
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
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // Maintained fork of JSch (pure Java SSH client).
    implementation("com.github.mwiede:jsch:0.2.21")

    testImplementation("junit:junit:4.13.2")
}
