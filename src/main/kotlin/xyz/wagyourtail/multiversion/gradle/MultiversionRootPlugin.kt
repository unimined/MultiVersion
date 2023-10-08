package xyz.wagyourtail.multiversion.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.multiversion.gradle.child.MultiversionExtensionImpl
import xyz.wagyourtail.multiversion.gradle.root.MultiversionRootExtensionImpl

class MultiversionRootPlugin : Plugin<Project> {

    companion object {
        val pluginVersion: String = MultiversionRootPlugin::class.java.`package`.implementationVersion ?: "unknown"
    }

    override fun apply(target: Project) {
        target.logger.lifecycle("[Multiversion] Root: ${target.path}")
        target.logger.lifecycle("[Multiversion] Plugin Version: $pluginVersion")

        target.extensions.create("multiversion", MultiversionRootExtensionImpl::class.java, target).apply {
            target.afterEvaluate {
                afterEvaluate()
            }
        }
    }

}