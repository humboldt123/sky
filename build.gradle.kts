plugins {
    kotlin("jvm") version "2.0.21"
    id("com.gradleup.shadow") version "8.3.5"
}

group = "me.bigratenthusiast"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Bukkit:2.12.3") {
        isTransitive = false
    }
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Core:2.12.3") {
        isTransitive = false
    }
    implementation(kotlin("stdlib"))
}

kotlin {
    jvmToolchain(21)
}

tasks {
    shadowJar {
        archiveClassifier.set("")
        relocate("kotlin", "me.bigratenthusiast.crypt.kotlin")
        relocate("org.jetbrains", "me.bigratenthusiast.crypt.jetbrains")
        relocate("org.intellij", "me.bigratenthusiast.crypt.intellij")
    }

    build {
        dependsOn(shadowJar)
    }

    processResources {
        filesMatching("plugin.yml") {
            expand("version" to version)
        }
    }
}
