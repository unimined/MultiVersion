package xyz.wagyourtail.multiversion.gradle.root

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ResolvedArtifact
import xyz.wagyourtail.multiversion.api.gradle.root.MultiversionRootExtension
import xyz.wagyourtail.multiversion.util.defaultedMapOf

open class MultiversionRootExtensionImpl(val project: Project) : MultiversionRootExtension {

    var mergeCheck: (ResolvedArtifact, ResolvedArtifact) -> Boolean = { a, b ->
        a.moduleVersion.id.group == b.moduleVersion.id.group && a.name == b.name && a.classifier == b.classifier
    }

    var subprojects: MutableMap<Project, List<String>> = mutableMapOf()

    var mergedDependencies: Configuration = project.configurations.maybeCreate("multiversionMergedDependencies")

    override fun mergable(mergeCheck: (ResolvedArtifact, ResolvedArtifact) -> Boolean) {
        this.mergeCheck = mergeCheck
    }

    override fun subproject(project: String, vararg configuration: String) {
        subproject(this.project.project(project), *configuration)
    }

    override fun subproject(project: Project, vararg configuration: String) {
        subprojects[project] = configuration.toList()
    }

    fun logMerge(merge: Map<ResolvedArtifact, Set<ResolvedArtifact>>) {
        project.logger.lifecycle("[Multiversion] Found ${merge.size} mergable dependencies")
        merge.forEach {
            project.logger.info("[Multiversion]   ${it.key}")
            it.value.forEach { artifact ->
                project.logger.info("[Multiversion]     $artifact")
            }
        }
    }

    fun afterEvaluate() {
        val dependencies = defaultedMapOf<Project, MutableSet<ResolvedArtifact>> { mutableSetOf() }
        subprojects.forEach { (project, configurations) ->
            configurations.forEach { configuration ->
                project.configurations.getByName(configuration).resolvedConfiguration.resolvedArtifacts.forEach {
                    dependencies[project].add(it)
                }
            }
        }
        val merged = defaultedMapOf<ResolvedArtifact, MutableSet<ResolvedArtifact>> {
            mutableSetOf()
        }
        dependencies.forEach { (project, artifacts) ->
            // only allow adding to 1 in merged, and only if didn't already add to it from this project
            val addedTo = mutableMapOf<ResolvedArtifact, ResolvedArtifact>()
            for (artifact in artifacts) {
                val key = merged.keys.firstOrNull { mergeCheck(it, artifact) }
                if (key != null) {
                    if (addedTo.containsKey(key)) {
                        throw IllegalStateException("Artifact $artifact is mergable with $key but already added ${addedTo[key]} from ${project.path}")
                    }
                    merged[key].add(artifact)
                    addedTo[key] = artifact
                } else {
                    merged[artifact].add(artifact)
                }
            }
        }
        logMerge(merged)
    }

}