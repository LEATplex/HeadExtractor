plugins {
    id("java")
    id("application")
    id("com.github.johnrengelman.shadow") version "7.1.2"
    id("maven-publish")
}

group = "me.amberichu.headextractor"
version = "1.0-SNAPSHOT"

java {
    withSourcesJar()
    withJavadocJar()
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

application {
    mainClass.set("me.amberichu.headextractor.HeadExtractor")
}


repositories {
    maven {
        url = uri("https://repo.opencollab.dev/main")
    }
    mavenCentral()
}

dependencies {
    implementation("com.github.steveice10", "opennbt", "1.5-SNAPSHOT")

    implementation("com.fasterxml.jackson.core", "jackson-core", "2.14.1")
    implementation("com.fasterxml.jackson.core", "jackson-databind", "2.14.1")
    implementation("com.fasterxml.jackson.dataformat", "jackson-dataformat-yaml", "2.14.1")
}

publishing {
    publications {
        register("mavenJava", MavenPublication::class) {
            from(components["java"])
        }
    }
}
