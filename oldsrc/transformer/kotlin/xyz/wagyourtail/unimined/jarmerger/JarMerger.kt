package xyz.wagyourtail.unimined.jarmerger

import xyz.wagyourtail.unimined.jarmerger.glue.GlueGun
import java.nio.file.Path
import java.util.*

class JarMerger(val workingFolder: Path) {

    fun merge(
        jars: Set<JarInfoWithDependencies>,
    ): Set<MergedJarInfoWithMergedDependencies> {
        val jars = uniqify(flatten(jars)).toMutableSet()
        // depth first process
        val processed = mutableMapOf<JarInfo, MergedJarInfo>()
        val byName = jars.groupBy { it.name }.toMutableMap()
        for ((key, nextJars) in byName) {
            val merged = MergedJarInfo(
                key,
                nextJars.toSet(),
                processed,
                workingFolder
            )
            for (jar in nextJars) {
                processed[jar] = merged
            }
        }
        // re-structure into same tree as originally, but with MergedJarInfoWithMergedDependencies
        return retree(jars, processed)
    }

    private fun flatten(jars: Set<JarInfo>): List<JarInfo> {
        return jars.flatMap {
            if (it is JarInfoWithDependencies) {
                flatten(it.dependencies)
            } else {
                setOf(it)
            }
        }
    }

    private fun uniqify(jars: List<JarInfo>): Set<JarInfo> {
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

    private fun retree(jars: Set<JarInfo>, map: Map<JarInfo, MergedJarInfo>): Set<MergedJarInfoWithMergedDependencies> {
        val merged = mutableSetOf<MergedJarInfoWithMergedDependencies>()
        for (jar in jars) {
            if (jar is JarInfoWithDependencies) {
                val mergedJar = map[jar]!!
                val mergedDependencies = retree(jar.dependencies, map)
                merged.add(MergedJarInfoWithMergedDependencies(mergedJar.path, mergedJar.name, mergedJar.versions.map { it.version }.toSet(), mergedDependencies))
            } else {
                val mergedJar = map[jar]!!
                merged.add(MergedJarInfoWithMergedDependencies(jar.path, jar.name, mergedJar.versions.map { it.version }.toSet(), emptySet()))
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
        val versions: Set<JarInfo>,
        mergedMap: Map<JarInfo, MergedJarInfo>,
        workingFolder: Path
    ) {

        val glueGun = GlueGun(name, versions, mergedMap, workingFolder)

        val path by lazy {
            glueGun.generate()
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

    class MergedJarInfoWithMergedDependencies(val path: Path, val name: String, val versions: Set<String>, val dependencies: Set<MergedJarInfoWithMergedDependencies>) {

    }

}