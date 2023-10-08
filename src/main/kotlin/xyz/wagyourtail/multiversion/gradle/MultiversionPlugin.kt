package xyz.wagyourtail.multiversion.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.multiversion.gradle.child.MultiversionExtensionImpl

class MultiversionPlugin : Plugin<Project> {
    companion object {
        val pluginVersion: String = MultiversionRootPlugin::class.java.`package`.implementationVersion ?: "unknown"
    }

    override fun apply(target: Project) {
        target.logger.lifecycle("[Multiversion] Child: ${target.path}")
        target.extensions.create("multiversion", MultiversionExtensionImpl::class.java, target)
    }

}