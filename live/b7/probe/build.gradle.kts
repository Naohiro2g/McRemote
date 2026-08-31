plugins {
    java
}

val paperApiVersion: String = providers.gradleProperty("paperApiVersion").get()
val probeJavaVersion: Int = providers.gradleProperty("probeJavaVersion").map(String::toInt).get()
val probeVersion: String = providers.gradleProperty("probeVersion").get()

group = "club.code2create"
version = probeVersion

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(probeJavaVersion))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
}

tasks.processResources {
    filesMatching("paper-plugin.yml") {
        expand("version" to probeVersion)
    }
}

tasks.jar {
    archiveFileName.set("mc-remote-b7-live-probe-$probeVersion.jar")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
