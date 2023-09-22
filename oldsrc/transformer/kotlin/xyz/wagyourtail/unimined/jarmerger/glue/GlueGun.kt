package xyz.wagyourtail.unimined.jarmerger.glue

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.SimpleRemapper
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import xyz.wagyourtail.unimined.jarmerger.JarMerger
import xyz.wagyourtail.unimined.jarmerger.defaultedMapOf
import xyz.wagyourtail.unimined.jarmerger.forEachInZip
import java.nio.file.Path
import java.security.MessageDigest

class GlueGun(val name: String, val jars: Set<JarMerger.JarInfo>, val dependencies: Map<JarMerger.JarInfo, JarMerger.MergedJarInfo>, val workingFolder: Path) {

    fun sanatizeVersionForJava(version: String): String {
        val startVers = if (version.startsWith("v")) {
            version.substring(1)
        } else {
            version
        }
        return "v${startVers.replace(Regex("[^a-zA-Z0-9]"), "_")}"
    }

    fun String.sha1(): String {
        val md = MessageDigest.getInstance("SHA-1")
        val digest = md.digest(this.toByteArray())
        val hexString = StringBuffer()
        for (i in digest.indices) {
            val hex = Integer.toHexString(0xff and digest[i].toInt())
            if (hex.length == 1) hexString.append('0')
            hexString.append(hex)
        }
        return hexString.toString()
    }

    val glueHash: String by lazy {
        val versions = jars.joinToString("-") { it.version }
        versions.sha1().substring(0..8)
    }

    val mergedJar = workingFolder.resolve("$name-glued-$glueHash.jar")

    fun getInheritanceTree(node: ClassNode, getSuper: (String) -> ClassNode?): List<String> {
        val tree = mutableListOf<String>()
        var current = node.name
        while (current != "java/lang/Object") {
            tree.add(current)
            current = getSuper(current)?.superName ?: "java/lang/Object"
        }
        tree.add("java/lang/Object")
        return tree
    }

    fun generate(): Path {
        mergedJar.parent.toFile().mkdirs()
        if (mergedJar.toFile().exists()) {
            mergedJar.toFile().delete()
        }

        val versionedClassNames = defaultedMapOf<String, MutableMap<String, String>> { mutableMapOf() }
        val classToVersions = defaultedMapOf<String, MutableMap<String, ClassNode>> { mutableMapOf() }

        // open jar
        for (jar in jars) {
            jar.path.forEachInZip { s, inputStream ->
                if (s.endsWith(".class")) {
                    val node = ClassNode()
                    val reader = ClassReader(inputStream)
                    reader.accept(node, 0)
                    classToVersions[node.name][jar.version] = node
                    versionedClassNames[jar.version][node.name] = sanatizeVersionForJava(jar.version) + "/" + node.name
                }
            }
        }

        val allVersions = versionedClassNames.keys

        val outputClasses = defaultedMapOf<String, ClassNode> { ClassNode() }
        for ((name, versions) in classToVersions) {
            val glueClass = outputClasses[name]
            glueClass.name = name

            // write versioned
            for ((version, node) in versions) {
                val remapper = SimpleRemapper(versionedClassNames[version])
                val output = outputClasses[versionedClassNames[version][name]]
                ClassRemapper(output, remapper).apply {
                    node.accept(this)
                }
            }

            // check if inheritance tree is compatible
            val inheritanceTrees = versions.map { entry -> getInheritanceTree(entry.value) { classToVersions[it][entry.key] } }
            val common = inheritanceTrees.reduce { acc, list -> acc.intersect(list.toSet()).toList() }
            if (common.isEmpty()) {
                throw IllegalStateException("Inheritance tree for $name is not compatible!")
            }

            glueClass.superName = common.first()
            glueClass.access = Opcodes.ACC_PUBLIC
            glueClass.version = Opcodes.V1_8
            glueClass.interfaces = versions.map { entry -> entry.value.interfaces }.reduce { acc, list -> acc.intersect(list.toSet()).toList() }

            val fieldsToVersions = defaultedMapOf<String, MutableMap<String, FieldNode>> { mutableMapOf() }
            for ((version, node) in versions) {
                for (field in node.fields) {
                    fieldsToVersions[field.name][version] = field
                }
            }






        }

    }

}