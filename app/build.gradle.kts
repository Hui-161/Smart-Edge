import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Every commit gets a higher versionCode, so new builds install as updates over older ones
val gitCommitCount: Int = try {
    // A shallow clone counts only part of the history and would produce a lower version than
    // earlier builds (Android then refuses the update as a downgrade).
    val shallow = providers.exec { commandLine("git", "rev-parse", "--is-shallow-repository") }
        .standardOutput.asText.get().trim()
    if (shallow == "true") {
        throw GradleException("Shallow git clone: run 'git fetch --unshallow' so the version number matches CI builds")
    }
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
        .standardOutput.asText.get().trim().toInt()
} catch (e: GradleException) {
    throw e
} catch (e: Exception) {
    0
}

android {
    namespace = "com.imi.smartedge.sidebar.panel"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.imi.smartedge.sidebar.panel"
        minSdk = 26
        targetSdk = 34
        versionCode = 1000 + gitCommitCount
        versionName = "1.4.0.$gitCommitCount"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resConfigs("en", "es", "de")
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    if (keystorePropertiesFile.exists()) {
        val keystoreProperties = Properties()
        keystoreProperties.load(FileInputStream(keystorePropertiesFile))

        signingConfigs {
            create("release") {
                keyAlias = keystoreProperties["KEY_ALIAS"] as String
                keyPassword = keystoreProperties["KEY_PASSWORD"] as String
                storeFile = file(keystoreProperties["STORE_FILE"] as String)
                storePassword = keystoreProperties["STORE_PASSWORD"] as String
            }
        }
    }

    // Fixed signing key for test builds, so every new APK installs as an update.
    // Provided by the DEV_KEYSTORE_BASE64 secret in CI (decoded to app/signing/dev.keystore, never committed).
    val devKeystore = file("signing/dev.keystore")
    if (devKeystore.exists()) {
        signingConfigs {
            create("dev") {
                storeFile = devKeystore
                storePassword = System.getenv("DEV_KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("DEV_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("DEV_KEY_PASSWORD") ?: "android"
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("dev")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only sign with the release key when keystore.properties exists (CI release builds)
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.testLogging { events("started", "passed", "failed", "skipped") }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.glide)
    implementation("com.github.skydoves:colorpickerview:2.3.0")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    implementation("dev.rikka.shizuku:api:12.1.0")
    implementation("dev.rikka.shizuku:provider:12.1.0")

    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
