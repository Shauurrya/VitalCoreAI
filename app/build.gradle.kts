import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Release signing, read from a file that is NOT in the repository.
 *
 * `keystore.properties` and `*.jks` are gitignored. A keystore committed to a public repo is
 * a keystore anyone can sign an update with, and Play Store app identity is bound to that key
 * for the lifetime of the listing — it cannot be rotated after the fact.
 *
 * Absent the file, `assembleRelease` still builds; it just produces an unsigned APK rather
 * than failing, so CI and a fresh clone both work without secrets.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use(::load)
}
val hasReleaseSigning = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.example.vitalcoreai"
    compileSdk = 36
    defaultConfig {
        // NOTE: `com.example.*` is reserved and is REJECTED by the Play Store. Publishing
        // there needs a real applicationId (the namespace above can stay, so no Kotlin
        // packages move) — but changing it makes this a different app to Android, so
        // existing installs will not upgrade. Left as-is deliberately; it is a branding
        // decision, not a technical one.
        applicationId = "com.example.vitalcoreai"
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Deliberately still off. Turning R8 on changes what ships, and nothing in this
            // build has been run on a device yet — enabling it at the same time as a large
            // feature drop would make any resulting crash impossible to attribute.
            // `proguard-rules.pro` now exists and carries the keep rules, so this is a
            // one-line flip once there has been a device pass.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Room exports each schema version as JSON so MigrationTestHelper can verify a
    // migration against the *previous* schema on disk, rather than against the entities
    // the current build just generated — which would make any migration test tautological.
    sourceSets {
        getByName("androidTest") {
            assets.directories.add("$projectDir/schemas")
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Lifecycle / Arch
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Compose
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.compose.animation)
    // NOTE: androidx.compose.ui:ui-text-google-fonts deliberately removed — a
    // downloadable font is a network dependency, and this app is offline-only.
    // See theme/Type.kt for the bundled-font path.

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Debug / tooling
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Hilt (KSP-based — requires Hilt 2.59.2+ for AGP9)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Health Connect
    implementation(libs.health.connect)

    // Biometric lock (T-17)
    implementation(libs.androidx.biometric)

    // Vico Charts
    implementation(libs.vico.compose)
    implementation(libs.vico.compose.m3)
    implementation(libs.vico.core)

    // WorkManager + Hilt Worker
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.mockito:mockito-core:5.11.0")
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.room.testing)
}

