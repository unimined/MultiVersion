package xyz.wagyourtail.unimined.jarmerger.glue

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import java.net.URLClassLoader

class ClassWriterASM(val loader: ClassLoader = URLClassLoader(arrayOf()), val buildingNodes: Map<String, ClassNode>): ClassWriter(
    COMPUTE_MAXS or COMPUTE_FRAMES
) {
    override fun getCommonSuperClass(type1: String, type2: String): String {
        val it1 = buildInheritanceTree(type1)
        val it2 = buildInheritanceTree(type2)
        val common = it1.intersect(it2.toSet())
        return common.first()
    }

    fun buildInheritanceTree(type1: String): List<String> {
        val tree = mutableListOf<String>()
        var current = type1
        while (current != "java/lang/Object") {
            tree.add(current)
            current = if (buildingNodes.containsKey(type1)) {
                buildingNodes[current]!!.superName
            } else {
                val currentClassFile = loader.getResource("/${current}.class")
                if (currentClassFile == null) {
                    "java/lang/Object"
                } else {
                    val classReader = ClassReader(currentClassFile.readBytes())
                    classReader.superName
                }
            }
        }
        tree.add("java/lang/Object")
        return tree
    }
}