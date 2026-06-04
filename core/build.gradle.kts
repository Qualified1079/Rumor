plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(17)

    jvm {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
                // expect/actual on objects is "Beta" per KT-61573 but functional and
                // documented; suppress the warning. Re-evaluate when KMP graduates it.
                freeCompilerArgs += listOf("-Xexpect-actual-classes")
            }
        }
    }

    // iOS targets — only enabled on macOS (Konan iOS toolchain requires Xcode).
    // Code in core/src/iosMain compiles on Mac builds; Linux/Windows skip the
    // iOS source set entirely so JVM workflows work everywhere.
    //
    // Actuals: CryptoKit + CommonCrypto back PlatformCrypto; Foundation backs
    // Base64Codec / Uuid; Security backs PlatformRandom; NSDate backs SystemClock.
    val isMac = org.jetbrains.kotlin.konan.target.HostManager.hostIsMac
    if (isMac) {
        iosX64()
        iosArm64()
        iosSimulatorArm64()

        targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
            compilations.all {
                kotlinOptions.freeCompilerArgs += listOf("-Xexpect-actual-classes")
            }
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlin.RequiresOptIn")
        }

        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
                // Multiplatform atomics: AtomicLong / AtomicReference / atomic
                // for the concurrency-primitive expect/actual surface. JetBrains-
                // maintained, used widely in KMP libs.
                implementation("org.jetbrains.kotlinx:atomicfu:0.23.1")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation("org.bouncycastle:bcprov-jdk15on:1.70")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation("junit:junit:4.13.2")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
                implementation("io.kotest:kotest-runner-junit5:5.8.0")
                implementation("io.kotest:kotest-property:5.8.0")
                // O28 wire-parser fuzzing. Jazzer-junit registers @FuzzTest methods as
                // ordinary JUnit5 tests by default (single corpus seed run); set the env
                // var JAZZER_FUZZ=1 to put them into in-process fuzzing mode for CI or
                // ad-hoc bug-hunting. https://github.com/CodeIntelligenceTesting/jazzer
                implementation("com.code-intelligence:jazzer-junit:0.22.1")
            }
        }

        // iOS source-set hierarchy: shared iosMain feeds the three iOS targets.
        // Only configured on Mac builds (where the targets are declared).
        if (isMac) {
            val iosX64Main by getting
            val iosArm64Main by getting
            val iosSimulatorArm64Main by getting
            val iosMain by creating {
                dependsOn(commonMain)
                iosX64Main.dependsOn(this)
                iosArm64Main.dependsOn(this)
                iosSimulatorArm64Main.dependsOn(this)
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
