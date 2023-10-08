package xyz.wagyourtail.multiversion.api.gradle.child

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.jvm.tasks.Jar
import xyz.wagyourtail.multiversion.api.gradle.root.MultiversionRootExtension
import java.io.File
import java.util.function.Consumer


val Project.multiversion
    get() = extensions.getByType(MultiversionExtension::class.java)

abstract class MultiversionExtension {

    abstract var version: String
    abstract var classpath: Set<File>

    abstract var configurations: MutableSet<Configuration>

    abstract fun configuration(name: String)

    abstract fun configuration(config: Configuration)

    abstract fun afterRoot()

    abstract fun splitJar(config: Jar.() -> Unit)

    fun splitJar(
        @DelegatesTo(value = Jar::class, strategy = Closure.DELEGATE_FIRST)
        config: Closure<*>
    ) {
        splitJar {
            config.delegate = this
            config.call()
        }
    }
}

