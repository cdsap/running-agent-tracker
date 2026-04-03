import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.compose") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    id("org.jetbrains.compose") version "1.10.2"
}

group = "com.agentsessiontab"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
}

compose.desktop {
    application {
        mainClass = "com.agentsessiontab.AppKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "AgentSessionTabCounter"
            packageVersion = "1.0.0"

            windows {
                menuGroup = "Agent session & tab counter"
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}
