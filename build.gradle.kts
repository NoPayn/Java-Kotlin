plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.serialization") version "1.9.25"
    `java-library`
    `maven-publish`
}

group = "io.nopayn"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.8.1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("NoPayn SDK")
                description.set("Official Kotlin/Java SDK for the NoPayn Payment Gateway")
                url.set("https://github.com/NoPayn/Java-Kotlin")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("costplus")
                        name.set("Cost+")
                        url.set("https://costplus.io")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/NoPayn/Java-Kotlin.git")
                    developerConnection.set("scm:git:ssh://github.com/NoPayn/Java-Kotlin.git")
                    url.set("https://github.com/NoPayn/Java-Kotlin")
                }
            }
        }
    }
}
