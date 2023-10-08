package xyz.wagyourtail.multiversion.gradle.child

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import xyz.wagyourtail.multiversion.api.gradle.child.MultiversionExtension
import xyz.wagyourtail.multiversion.api.gradle.child.multiversion
import xyz.wagyourtail.multiversion.split.SplitProvider
import xyz.wagyourtail.multiversion.util.LazyMutable
import xyz.wagyourtail.multiversion.util.main
import xyz.wagyourtail.multiversion.util.sourceSets
import xyz.wagyourtail.multiversion.util.FinalizeOnRead
import xyz.wagyourtail.multiversion.util.MustSet
import java.io.File


open class MultiversionExtensionImpl(val project: Project) : MultiversionExtension() {


    override var version: String by FinalizeOnRead(MustSet())
    override var classpath: Set<File> by FinalizeOnRead(LazyMutable { project.sourceSets.main.compileClasspath.files })
    override var configurations = mutableSetOf<Configuration>(project.configurations.maybeCreate("mergeImplementation").apply {
        project.configurations.getByName("implementation").extendsFrom(this)
    })
    var splitTask: Jar.() -> Unit = {}

    override fun configuration(name: String) {
        configurations += project.configurations.maybeCreate(name)
    }

    override fun configuration(config: Configuration) {
        configurations += config
    }

    override fun splitJar(config: Jar.() -> Unit) {
        splitTask = config
    }

    init {
        init()
    }

    fun init() {
        project.logger.lifecycle("[Multiversion] Child project ${project.name} initialized")
    }

    override fun afterRoot() {
        // add project(parent, configuration: "splitTo_$version") to implementation
//        val dep = project.dependencies.project(mapOf("path" to project.parent!!.path, "configuration" to "splitTo_$version"))
//        project.configurations.getByName("implementation").dependencies.add(dep)
        val split = project.tasks.register("splitJarTo_$version", Jar::class.java) {
            it.group = "multiversion-internal"
            it.description = "Splits the build jar into a version specific jar"
            it.archiveClassifier.set(version)
            it.destinationDirectory.set(project.buildDir.resolve("tmp").resolve("splitJarTo_$version"))

            it.dependsOn(this.project.parent!!.tasks.named("jar"))

            it.doFirst {
                project.logger.lifecycle("[Multiversion/${project.path}] Splitting build jar for $version")
                val inputFile = project.parent!!.tasks.named("jar", Jar::class.java).get().archiveFile.get().asFile.toPath()
                val tempOut = project.buildDir.toPath().resolve("tmp").resolve("splitJarTo_$version").resolve("$version.jar")
                SplitProvider.split(inputFile, project.parent!!.sourceSets.main.compileClasspath.map { it.toPath() }.toSet(), version, tempOut)
                (it as Jar).from(project.zipTree(tempOut.toFile()))
            }

            splitTask(it)
        }
        project.configurations.maybeCreate("splitTo").also {
            project.sourceSets.main.compileClasspath += it
            project.sourceSets.main.runtimeClasspath += it
            // because idea doesn't like the "proper" way
            it.dependencies.add(project.dependencies.create(project.files(split.get().archiveFile.get().asFile)))
        }
        // the "proper" way
        project.artifacts.add("splitTo", split.get())
        // so that compile can depend on it
        project.tasks.named("compileJava", JavaCompile::class.java) {
            it.dependsOn(split.get())
        }
        // also add to jar's output
        project.tasks.named("jar", Jar::class.java) {
            it.dependsOn(split.get())
            it.doFirst {
                (it as Jar).from(project.zipTree(split.get().archiveFile.get().asFile))
            }
        }
    }

}