plugins {
    `java-library`
    `maven-publish`
}

version = "1.1.6"

repositories {
    mavenCentral()
}

dependencies {
    api(group = "com.electronwill.night-config", name = "core", version = "3.6.6")

    testImplementation(group = "com.electronwill.night-config", name = "toml", version = "3.6.6")
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