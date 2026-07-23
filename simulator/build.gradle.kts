plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.rumor.mesh.simulator.MainKt")
}

dependencies {
    implementation(project(":core"))

    // Coroutines with virtual time support
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Ktor embedded server + WebSocket
    val ktor = "2.3.7"
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-netty:$ktor")
    implementation("io.ktor:ktor-server-websockets:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Zip for reports
    implementation("org.apache.commons:commons-compress:1.26.0")

    testImplementation("junit:junit:4.13.2")
}

// Each SimNode is a full engine graph (scopes, flows). Many heavy scenario
// classes in one JVM accrete live nodes and OOM the default heap under the
// parallel run (see docs/SIMULATOR_TESTING.md §4). Give the test JVM room and
// restart it periodically so leaked state can't accumulate across the suite.
tasks.test {
    maxHeapSize = "2g"
    forkEvery = 15
}

// Fat jar so `java -jar simulator.jar` just works with no classpath setup.
tasks.jar {
    manifest { attributes["Main-Class"] = "com.rumor.mesh.simulator.MainKt" }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // BouncyCastle (and a few other deps) ship signed JARs; merging them into a
    // fat jar invalidates the signature files and the JVM refuses to launch
    // with 'Invalid signature file digest for Manifest main attributes'. Drop
    // the signature manifest entries from the bundled artifact — Rumor doesn't
    // rely on JAR-level signing for trust, only on Ed25519 over wire payloads.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
}
