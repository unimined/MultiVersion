package xyz.wagyourtail.multiversion.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.multiversion.gradle.child.MultiversionExtensionImpl

class MultiversionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.extensions.create("multiversion", MultiversionExtensionImpl::class.java, target)
    }

}