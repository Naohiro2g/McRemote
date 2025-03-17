plugins {
    `java-library`
    `maven-publish`
    id("com.gradleup.shadow") version "8.3.6"
}

java.sourceCompatibility = JavaVersion.VERSION_21

group = "club.code2create"
description = "mc-remote: Minecraft Remote Control Plugin"

val mcVersion: String = project.findProperty("mcVersion") as String? ?: "1.1.1"
val pluginVersion: String = project.findProperty("pluginVersion") as String? ?: "0.0.0"
val version = "$mcVersion-$pluginVersion"

tasks.jar {
    archiveBaseName.set("mc-remote")
    archiveVersion.set("$version")
    archiveClassifier.set("")
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand(mapOf("project" to mapOf("version" to version)))
    }
}


repositories {
    mavenCentral()
    maven("papermc") {
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("net.luckperms:api:5.4")
}
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}
publishing {
    publications.create<MavenPublication>("maven") {
        from(components["java"])
    }
}
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
tasks.withType<Javadoc> {
    options.encoding = "UTF-8"
}
