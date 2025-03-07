group = "org.wensheng"
version = "1.21.4"
description = "juicyraspberrypie"
java.sourceCompatibility = JavaVersion.VERSION_21


tasks.processResources {
    filesMatching("plugin.yml") {
        expand(mapOf("project" to project))
    }
}

plugins {
    `java-library`
    `maven-publish`
}
repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    mavenCentral()
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
tasks.withType<JavaCompile>() {
    options.encoding = "UTF-8"
}
tasks.withType<Javadoc>() {
    options.encoding = "UTF-8"
}
