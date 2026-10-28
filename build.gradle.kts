plugins {
    `java-library`
    `maven-publish`
}

version = "2.0.0"

repositories {
    mavenCentral()
}

dependencies {
    api("com.electronwill.night-config:toml:3.9.0")
}

tasks.compileJava {
    options.release = 17 // Same as jitpack.yml, whatever JDK runs Gradle
}

tasks.test {
    failOnNoDiscoveredTests = false // src/test only contains an example, no unit tests
}

tasks.jar {
    manifest {
        attributes(mapOf("Implementation-Title" to project.name,
                "Implementation-Version" to project.version))
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}