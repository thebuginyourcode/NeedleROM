import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Integration tests against real ROMs run only when NEEDLE_TEST_ROMS points at a folder of them.
    environment("NEEDLE_TEST_ROMS", System.getenv("NEEDLE_TEST_ROMS") ?: "")
    maxHeapSize = "2g"
}
