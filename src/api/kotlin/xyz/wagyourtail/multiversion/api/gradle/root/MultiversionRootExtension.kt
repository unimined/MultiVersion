package xyz.wagyourtail.multiversion.api.gradle.root

import groovy.lang.Closure
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ResolvedArtifact

interface MultiversionRootExtension {

    fun mergable(mergeCheck: (ResolvedArtifact, ResolvedArtifact) -> Boolean)

    fun mergable(
        @ClosureParams(
            value = SimpleType::class,
            options = [
                "org.gradle.api.artifacts.ResolvedArtifact,org.gradle.api.artifacts.ResolvedArtifact"
            ]
        )
        mergeCheck: Closure<Boolean>
    ) {
        mergable(mergeCheck::call)
    }

    fun subproject(project: String, vararg configuration: String)

    fun subproject(project: String, configuration: List<String>) {
        subproject(project, *configuration.toTypedArray())
    }

    fun subproject(project: Project, vararg configuration: String)

    fun subproject(project: Project, configuration: List<String>) {
        subproject(project, *configuration.toTypedArray())
    }

}