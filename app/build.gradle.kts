import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Release signing is configured via app/keystore.properties (gitignored).
// Template lives at app/keystore.properties.example. Without the file, the
// release build still compiles but produces an unsigned APK — useful for CI
// reproducibility checks; F-Droid does its own signing.
val keystorePropsFile = rootProject.file("app/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.rumor.mesh"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rumor.mesh"
        minSdk = 23
        targetSdk = 34
        // Bump BOTH on every flashed change so asymmetric installs across the
        // test fleet are detectable via `dumpsys package … | grep version`
        // (G7 also enforces monotonic versionCode at release-tag time).
        versionCode = 9
        versionName = "0.4.5-ingest-order"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // BouncyCastle ships duplicate signed jar entries
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            pickFirsts += "META-INF/BCKEY.DSA"
            pickFirsts += "META-INF/BCKEY.SF"
        }
    }
}

dependencies {
    implementation(project(":core"))
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)

    // Android core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // Koin — FOSS dependency injection (Apache 2.0, no Google)
    implementation("io.insert-koin:koin-android:3.5.3")
    implementation("io.insert-koin:koin-androidx-compose:3.5.3")

    // Room — to be replaced with SQLDelight in the follow-up commit.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Crypto — BouncyCastle for Ed25519 / X25519 on API < 33
    // Keep in lockstep with :core (jdk15on line is discontinued at 1.70).
    implementation("org.bouncycastle:bcprov-jdk18on:1.85")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // koin-test provides Module.verify() for static DI wiring checks (no Android runtime needed).
    testImplementation("io.insert-koin:koin-test:3.5.3")
    // O28 wire-parser fuzzing — bridge-codec harnesses for the Meshtastic and
    // MeshCore protobuf paths live in :app because the codec objects are
    // internal to this module. Same Jazzer setup as in :core.
    testImplementation("com.code-intelligence:jazzer-junit:0.22.1")
    // JUnit 5 engine + JUnit-4-on-5 bridge. The android.testOptions block below
    // enables `useJUnitPlatform()` so Jazzer @FuzzTest methods register as JUnit
    // 5 tests; without an engine on the classpath the platform fails to start
    // ("Cannot create Launcher without at least one TestEngine"). vintage-engine
    // lets the existing JUnit-4 tests keep running unchanged.
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

// Surface JUnit5's platform so Jazzer @FuzzTest methods get picked up by
// `gradle :app:testDebugUnitTest`. The bridge-codec fuzzers (Meshtastic /
// MeshCore protobuf paths) live in :app/src/test/.
android.testOptions {
    unitTests.all {
        it.useJUnitPlatform()
    }
}
