plugins {
    kotlin("jvm") version "2.4.21"
    id("io.ktor.plugin") version "3.6.0"
}

group = "organizer"
version = "0.1.0"

application {
    mainClass = "organizer.MainKt"
    // Photos are decoded with Java's image classes; headless keeps them from starting a desktop UI.
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-html-builder")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-server-default-headers")
    implementation("io.ktor:ktor-server-auth")
    implementation("io.ktor:ktor-server-csrf")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.anthropic:anthropic-java:3.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("ch.qos.logback:logback-classic:1.6.5")

    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation(kotlin("test"))
}

ktor {
    fatJar {
        archiveFileName = "organizer.jar"
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
}
