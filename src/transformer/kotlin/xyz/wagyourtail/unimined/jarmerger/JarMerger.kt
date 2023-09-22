package xyz.wagyourtail.unimined.jarmerger

import xyz.wagyourtail.unimined.jarmerger.glue.GlueStick
import java.nio.file.Path
import java.util.*

class GlueGun(val workingFolder: Path) {

    fun merge(
        jars: Set<JarInfoWithDependencies>,
    ): Set<MergedJarInfoWithMergedDependencies> {
        val jars = uniqify(flatten(jars)).toMutableSet()
        // depth first process
        val processed = mutableMapOf<JarInfo, MergedJarInfo>()
        val byName = jars.groupBy { it.name }.toMutableMap()
        while (byName.isNotEmpty()) {
            val next = byName.firstNotNullOf { (name, jars) ->
                for (jar in jars) {
                    if (jar is JarInfoWithDependencies && jar.flattenedDependencies.any { !processed.containsKey(it) }) {
                        return@firstNotNullOf null
                    }
                }
                return@firstNotNullOf name
            }
            val nextJars = byName.remove(next)!!.toSet()
            val merged = merge(nextJars, processed)
            for (jar in nextJars) {
                processed[jar] = merged
            }
        }
        // re-structure into same tree as originally, but with MergedJarInfoWithMergedDependencies
        return retree(jars, processed) as Set<MergedJarInfoWithMergedDependencies>
    }

    fun flatten(jars: Set<JarInfo>): List<JarInfo> {
        return jars.flatMap {
            if (it is JarInfoWithDependencies) {
                flatten(it.dependencies)
            } else {
                setOf(it)
            }
        }
    }

    fun uniqify(jars: List<JarInfo>): Set<JarInfo> {
        val uniq = mutableMapOf<JarInfo, JarInfo>()
        for (jar in jars) {
            if (jar in uniq) {
                if (jar is JarInfoWithDependencies) {
                    val existing = uniq[jar]!!
                    if (existing is JarInfoWithDependencies) {
                        // merge dependencies
                        val merged = JarInfoWithDependencies(
                            jar.path,
                            jar.name,
                            jar.version,
                            (existing.dependencies + jar.dependencies).toSet()
                        )
                        uniq[merged] = merged
                    } else {
                        uniq[jar] = jar
                    }
                }
            } else {
                uniq[jar] = jar
            }
        }
        return uniq.values.toSet()
    }

    fun merge(jars: Set<JarInfo>, processed: Map<JarInfo, MergedJarInfo>): MergedJarInfo {

    }

    fun retree(jars: Set<JarInfo>, map: Map<JarInfo, MergedJarInfo>): Set<MergedJarInfo> {
        val merged = mutableSetOf<MergedJarInfo>()
        for (jar in jars) {
            if (jar is JarInfoWithDependencies) {
                val mergedJar = map[jar]!!
                val mergedDependencies = retree(jar.dependencies, map)
                merged.add(MergedJarInfoWithMergedDependencies(mergedJar.path, mergedJar.name, mergedJar.versions, mergedDependencies))
            } else {
                merged.add(map[jar]!!)
            }
        }
        return merged
    }

    open class JarInfo(
        val path: Path,
        val name: String,
        val version: String
    ) {
        companion object {
            fun copy(jar: JarInfo): JarInfo {
                return JarInfo(jar.path, jar.name, jar.version)
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is JarInfo) return false

            return (name == other.name) && (version == other.version)
        }

        override fun hashCode(): Int {
            return Objects.hash(name, version)
        }
    }

    open class MergedJarInfo(
        val name: String,
        versions: Set<JarInfo>,
        mergedMap: Map<JarInfo, MergedJarInfo>,
        workingFolder: Path
    ) {

        val glueStick = GlueStick(name, versions, mergedMap, workingFolder)

        val path by lazy {

        }

    }

    class JarInfoWithDependencies(path: Path, name: String, version: String, val dependencies: Set<JarInfo>) : JarInfo(path, name, version) {

        fun hasDependency(jarInfo: JarInfo): Boolean {
            return dependencies.any { it == jarInfo || (it is JarInfoWithDependencies && it.hasDependency(jarInfo)) }
        }

        val flattenedDependencies: Set<JarInfo> by lazy {
            dependencies.flatMap {
                if (it is JarInfoWithDependencies) {
                    it.flattenedDependencies + setOf(it)
                } else {
                    setOf(it)
                }
            }.toSet()
        }

    }

    class MergedJarInfoWithMergedDependencies(path: Path, name: String, versions: Set<String>, val dependencies: Set<MergedJarInfo>) : MergedJarInfo(path, name, versions) {

    }

}