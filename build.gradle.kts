import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.5.30"
    kotlin("plugin.serialization") version "1.5.30"
    antlr
    application
}

group = "space.rymiel"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin", "kotlin-reflect", "1.5.30")
    antlr("org.antlr", "antlr4", "4.9.2")
}

tasks.withType<KotlinCompile> {
    dependsOn(tasks.withType<AntlrTask>())
    kotlinOptions.jvmTarget = "16"
}

tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    manifest {
        attributes["Main-Class"] = "space.rymiel.lncf.MainKt"
        attributes["Implementation-Title"] = "rymiel/LNCF Bytecode Compiler/Kotlin"
        attributes["Implementation-Version"] = version
    }
    
    from(configurations
        .runtimeClasspath.get().files
        .map { if (it.isDirectory) it else zipTree(it) })
}
