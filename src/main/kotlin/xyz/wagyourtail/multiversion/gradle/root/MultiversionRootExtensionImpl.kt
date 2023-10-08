package xyz.wagyourtail.multiversion.gradle.root

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.jvm.tasks.Jar
import xyz.wagyourtail.multiversion.api.gradle.child.MultiversionExtension
import xyz.wagyourtail.multiversion.api.gradle.child.multiversion
import xyz.wagyourtail.multiversion.api.gradle.root.MultiversionRootExtension
import xyz.wagyourtail.multiversion.gradle.MultiversionRootPlugin
import xyz.wagyourtail.multiversion.gradle.child.MultiversionExtensionImpl
import xyz.wagyourtail.multiversion.merge.MergeProvider
import xyz.wagyourtail.multiversion.split.SplitProvider
import xyz.wagyourtail.multiversion.util.*
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists

typealias Version = String
typealias ConfigName = String
typealias MergedName = String

open class MultiversionRootExtensionImpl(val project: Project) : MultiversionRootExtension() {

    var mergeName: (ResolvedArtifact) -> String = { a ->
        "${a.moduleVersion.id.group}:${a.name}"
    }

    var subprojects: MutableSet<Project> = mutableSetOf()

    override val mergedDependencies: Configuration = project.configurations.maybeCreate("multiversionMergedDependencies").also {
        project.sourceSets.main.compileClasspath += it
        it.dependencies.add(project.dependencies.create("xyz.wagyourtail.multiversion:multiversion:${MultiversionRootPlugin.pluginVersion}:inject"))
    }

    override fun mergeName(mergeName: (ResolvedArtifact) -> String) {
        this.mergeName = mergeName
    }

    override fun subproject(project: String, config: MultiversionExtension.() -> Unit) {
        subproject(this.project.project(project), config)
    }

    override fun subproject(sub: Project, config: MultiversionExtension.() -> Unit) {
        subprojects.add(sub)
        sub.plugins.apply("xyz.wagyourtail.multiversion")
        sub.multiversion.apply(config)
//            project.configurations.maybeCreate("splitTo_${it.multiversion.version}").also {
//                it.isCanBeResolved = false
//                it.isCanBeConsumed = true
//            }
        sub.multiversion.afterRoot()
        project.evaluationDependsOn(sub.path)
    }

    fun logMerge(merge: Map<MergedName, Set<Pair<Version, ResolvedArtifact>>>) {
        project.logger.lifecycle("[Multiversion] Found ${merge.size} mergable dependencies")
        merge.forEach {
            project.logger.info("[Multiversion]   ${it.key}")
            it.value.forEach { artifact ->
                project.logger.info("[Multiversion]     $artifact")
            }
        }
    }

    val evaluated = mutableSetOf<Project>()

    fun afterEvaluate() {
        project.logger.lifecycle("[Multiversion/${project.path}] Resolving mergable dependencies")

        val dependencies = defaultedMapOf<Project, DefaultMap<ConfigName, MutableSet<ResolvedArtifact>>> { defaultedMapOf { mutableSetOf() } }
        subprojects.forEach { project ->
            project.multiversion.configurations.forEach { config ->
                config.resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
                    dependencies[project][config.name].add(artifact)
                }
            }
        }

        project.tasks.register("syncSubprojects") {
            it.group = "multiversion"
            it.description = "Syncs subprojects with root project, by building their version specific jars"
            subprojects.forEach { sub ->
                sub.logger.lifecycle("[Multiversion] Syncing subproject ${sub.path}")
                it.dependsOn(sub.tasks.getByName("splitJarTo_${sub.multiversion.version}"))
            }
        }

        if (dependencies.isEmpty() || dependencies.all { it.value.isEmpty() }) {
            project.logger.lifecycle("[Multiversion/${project.path}] No mergable dependencies found")
        }

        val merged = defaultedMapOf<ConfigName, DefaultMap<MergedName, MutableSet<Pair<Version, ResolvedArtifact>>>> {
            defaultedMapOf { mutableSetOf() }
        }
        dependencies.forEach { (project, configurations) ->
            val version = project.multiversion.version
            for ((name, artifacts) in configurations) {
                // only allow adding to 1 in merged, and only if didn't already add to it from this project
                val addedTo = mutableMapOf<String, ResolvedArtifact>()
                for (artifact in artifacts) {
                    val key = mergeName(artifact)
                    if (addedTo.containsKey(key)) {
                        throw IllegalStateException("Artifact $artifact is mergable with $key but already added ${addedTo[key]} from ${project.path}")
                    }
                    merged[name][key].add(version to artifact)
                    addedTo[key] = artifact
                }
            }
        }

        val mergedToConfigs = merged.mapValues { it.value.keys }.inverseFlatMulti()
        val allMerged = merged.values.flatMap { it.entries }.associate { it.key to it.value.associate { it.first to it.second.file.toPath() } }
        val outputs = allMerged.keys.associateWith { project.projectDir.resolve(".gradle/multiversion/merged/${it.substringAfter(":")}-${allMerged[it]!!.keys.joinToString("+")}.jar").toPath() }
        project.projectDir.resolve(".gradle/multiversion/merged").toPath().createDirectories()
        if (project.properties["multiversion.forceReload"] == "true") {
            outputs.values.forEach {
                it.deleteIfExists()
            }
        }
        project.logger.lifecycle("[Multiversion/${project.path}] Merging ${allMerged.size} dependencies")
        MergeProvider.mergeAll(allMerged, outputs)
        // add to configs
        for ((mergedName, configs) in mergedToConfigs) {
            for (config in configs) {
                project.configurations.maybeCreate(config).also {
                    mergedDependencies.extendsFrom(it)
                    it.dependencies.add(project.dependencies.create(project.files(outputs[mergedName])))
                }
            }
        }
    }

}