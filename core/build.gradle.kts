plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}

dependencies {
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Atomics for AtomicCounter (kept from the KMP era; JVM transform is
    // compile-time, no runtime dep weight concern)
    implementation("org.jetbrains.kotlinx:atomicfu:0.23.1")

    // Crypto — BouncyCastle for Ed25519 / X25519. jdk18on is the maintained
    // line — jdk15on ended at 1.70 (2021) with CVEs fixed only in jdk18on.
    // Same org.bouncycastle packages; keep :app in lockstep.
    implementation("org.bouncycastle:bcprov-jdk18on:1.85")

    // Real MurmurHash3 for BloomFilterData — the previous hand-rolled mix
    // wasn't murmur despite the name. Pure Java, tiny, F-Droid compatible.
    implementation("commons-codec:commons-codec:1.22.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.kotest:kotest-runner-junit5:5.8.0")
    testImplementation("io.kotest:kotest-property:5.8.0")
    // O28 wire-parser fuzzing. Jazzer-junit registers @FuzzTest methods as
    // ordinary JUnit5 tests by default (single corpus seed run); set the env
    // var JAZZER_FUZZ=1 to put them into in-process fuzzing mode for CI or
    // ad-hoc bug-hunting. https://github.com/CodeIntelligenceTesting/jazzer
    testImplementation("com.code-intelligence:jazzer-junit:0.22.1")
    // Run plain JUnit4 tests (e.g. routing/) on the JUnit5 platform below.
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
}

// Surface Jazzer's JUnit5 platform so the @FuzzTest methods actually run when
// invoking `gradle :core:test`. Kotest already pulls JUnit5; Jazzer plays
// nicely with it.
tasks.test {
    useJUnitPlatform()
}
