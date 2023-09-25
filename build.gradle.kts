import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.8.21"
    `java-gradle-plugin`
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

val main = sourceSets.main

val api by sourceSets.registering {
    compileClasspath += main.get().compileClasspath
    runtimeClasspath += main.get().runtimeClasspath
}

val inject by sourceSets.registering {
    compileClasspath += main.get().compileClasspath
    runtimeClasspath += main.get().runtimeClasspath
}

val merge by sourceSets.registering {
    compileClasspath += api.get().output + main.get().compileClasspath
    runtimeClasspath += api.get().output + main.get().runtimeClasspath
}

val split by sourceSets.registering {
    compileClasspath += api.get().output + main.get().compileClasspath
    runtimeClasspath += api.get().output + main.get().runtimeClasspath
}

main {
    compileClasspath += api.get().output + inject.get().output + merge.get().output + split.get().output
    runtimeClasspath += api.get().output + inject.get().output + merge.get().output + split.get().output
}

sourceSets.test {
    compileClasspath += api.get().output + inject.get().output + merge.get().output + split.get().output + main.get().output + main.get().compileClasspath
    runtimeClasspath += api.get().output + inject.get().output + merge.get().output + split.get().output + main.get().output + main.get().runtimeClasspath
}


val testMergeA by sourceSets.registering {

}

val testMergeB by sourceSets.registering {

}

val testMerged by sourceSets.registering {
    compileClasspath += inject.get().output
    runtimeClasspath += inject.get().output
}

val testSplit by sourceSets.registering {
    compileClasspath += inject.get().output
    runtimeClasspath += inject.get().output
}

val asmVersion = project.properties["asm_version"] as String

repositories {
    mavenCentral()
}

dependencies {
    implementation(gradleApi())
    implementation("org.ow2.asm:asm:$asmVersion")
    implementation("org.ow2.asm:asm-commons:$asmVersion")
    implementation("org.ow2.asm:asm-tree:$asmVersion")
    implementation("org.ow2.asm:asm-util:$asmVersion")
    implementation("org.ow2.asm:asm-analysis:$asmVersion")

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
    from(
        main.get().output,
        api.get().output,
        inject.get().output,
        merge.get().output,
        split.get().output
    )

    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version
        )
    }
}

val injectJar by tasks.registering(Jar::class) {
    from(inject.get().output)
    archiveClassifier.set("inject")
}

val testMergeAJar by tasks.registering(Jar::class) {
    from(testMergeA.get().output)
    archiveFileName.set("test-merge-a.jar")
}

val testMergeBJar by tasks.registering(Jar::class) {
    from(testMergeB.get().output)
    archiveFileName.set("test-merge-b.jar")
}

val testMergedJar by tasks.registering(Jar::class) {
    from(testMerged.get().output)
    archiveFileName.set("test-merged.jar")

}

tasks.test {
    dependsOn(testMergeAJar, testMergeBJar, testMergedJar)
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        create("mv-root") {
            id = "xyz.wagyourtail.multiversion-root"
            implementationClass = "xyz.wagyourtail.multiversion.gradle.MultiversionRootPlugin"
            version = project.version as String
        }
        create("mv") {
            id = "xyz.wagyourtail.multiversion"
            implementationClass = "xyz.wagyourtail.multiversion.gradle.MultiversionPlugin"
            version = project.version as String
        }
    }
}

publishing {
    repositories {
        maven {
            name = "WagYourMaven"
            url = if (project.hasProperty("version_snapshot")) {
                uri("https://maven.wagyourtail.xyz/snapshots/")
            } else {
                uri("https://maven.wagyourtail.xyz/releases/")
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

            artifact(tasks.jar.get())
            artifact(injectJar.get()) {
                classifier = "inject"
            }
        }
    }
}