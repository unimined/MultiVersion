package xyz.wagyourtail.multiversion.api.gradle.root

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedArtifact
import xyz.wagyourtail.multiversion.api.gradle.child.MultiversionExtension
import java.util.function.Consumer

val Project.multiversionRoot
    get() = extensions.getByType(MultiversionRootExtension::class.java)

@Suppress("OVERLOADS_ABSTRACT", "UNUSED")
abstract class MultiversionRootExtension {

    abstract val mergedDependencies: Configuration


    abstract fun mergeName(mergeName: (ResolvedArtifact) -> String)

    fun mergeName(
        @ClosureParams(
            value = SimpleType::class,
            options = [
                "org.gradle.api.artifacts.ResolvedArtifact"
            ]
        )
        mergeName: Closure<String>
    ) {
        mergeName(mergeName::call)
    }

    abstract fun subproject(project: String, config: MultiversionExtension.() -> Unit)

    fun subproject(project: String,
        @DelegatesTo(value = MultiversionExtension::class, strategy = Closure.DELEGATE_FIRST)
        config: Closure<*>
   ) {
        subproject(project) {
            config.delegate = this
            config.call()
        }
    }

    @JvmOverloads
    abstract fun subproject(project: Project, config: MultiversionExtension.() -> Unit = {})

    fun subproject(project: Project,
        @DelegatesTo(value = MultiversionExtension::class, strategy = Closure.DELEGATE_FIRST)
        config: Closure<*>
    ) {
        subproject(project) {
            config.delegate = this
            config.call()
        }
    }

}