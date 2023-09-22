import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URI

plugins {
    kotlin("jvm") version "1.8.21"
    `maven-publish`
}

group = project.properties["maven_group"] as String
version = project.properties["version"] as String

base {
    archivesName.set(project.properties["archives_base_name"] as String)
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

sourceSets {
    register("transformer") {
        compileClasspath += main.get().output + main.get().compileClasspath
        runtimeClasspath += main.get().output + main.get().runtimeClasspath
    }
    register("runtime") {
        compileClasspath += main.get().output + main.get().compileClasspath
        runtimeClasspath += main.get().output + main.get().runtimeClasspath
    }
    test {
        compileClasspath += main.get().output + sourceSets["transformer"].output + sourceSets["runtime"].output
        runtimeClasspath += main.get().output + sourceSets["transformer"].output + sourceSets["runtime"].output
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.ow2.asm:asm:9.5")
    implementation("org.ow2.asm:asm-commons:9.5")
    implementation("org.ow2.asm:asm-tree:9.5")
    implementation("org.ow2.asm:asm-util:9.5")
    implementation("org.ow2.asm:asm-analysis:9.5")

    testImplementation(kotlin("test"))
}

tasks.withType<JavaCompile> {
    val targetVersion = 8
    if (JavaVersion.current().isJava9Compatible) {
        options.release.set(targetVersion)
    }
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

tasks.jar {
    from(sourceSets["transformer"].output, sourceSets["runtime"].output, sourceSets["main"].output)
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    repositories {
        maven {
            name = "WagYourMaven"
            url = if (project.hasProperty("version_snapshot")) {
                URI.create("https://maven.wagyourtail.xyz/snapshots/")
            } else {
                URI.create("https://maven.wagyourtail.xyz/releases/")
            }
            credentials {
                username = project.findProperty("mvn.user") as String? ?: System.getenv("USERNAME")
                password = project.findProperty("mvn.key") as String? ?: System.getenv("TOKEN")
            }
        }
    }
    publications {
        create<MavenPublication>("maven") {
            groupId = project.group as String
            artifactId = project.properties["archives_base_name"] as String? ?: project.name
            version = project.version as String

            from(components["java"])
        }
    }
}