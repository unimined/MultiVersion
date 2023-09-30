package xyz.wagyourtail.multiversion.util.asm

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import java.util.*
import kotlin.collections.ArrayList


fun getSuperTypes(type: String, classes: Map<Type, ClassNode>): List<String> {
    val l: MutableList<String> = ArrayList()
    var current: String? = type
    while (current != "java/lang/Object") {
        l.add(current!!)
        try {
            val c = Class.forName(current.replace('/', '.'))
            current = c.superclass.getName().replace('.', '/')
        } catch (e: Throwable) {
            current = classes[Type.getObjectType(current)]?.superName
            if (current == null) {
                current = "java/lang/Object"
                System.err.println("Could not find super type for $type")
            }
        }
    }
    l.add("java/lang/Object")
    return l
}

fun getSuperTypesAndInterfaces(type: String, classes: Map<Type, ClassNode>): List<String> {
    val l: MutableList<String> = ArrayList()
    val next = LinkedList(listOf(type))
    var current: String? = null
    while (!next.isEmpty()) {
        current = next.removeFirst()
        l.add(current!!)
        try {
            val c = Class.forName(current.replace('/', '.'))
            val inher = c.interfaces.map { it.name.replace('.', '/') }.toMutableList()
            val sc = c.superclass?.name?.replace('.', '/')
            if (sc != null && sc != "java/lang/Object") {
                inher.add(sc)
            }
            next.addAll(0, inher)
        } catch (e: Throwable) {
            val node = classes[Type.getObjectType(current)]
            if (node != null) {
                val inher = node.interfaces.toMutableList()
                val sc = node.superName
                if (sc != "java/lang/Object") {
                    inher.add(sc)
                }
                next.addAll(0, inher)
            } else {
                System.err.println("Could not find super type for $current")
            }
        }
    }
    l.add("java/lang/Object")
    return l
}

fun findMemberCallReadTarget(member: FullyQualifiedMember, classes: Map<Type, ClassNode>) {
    if (member.type.sort == Type.METHOD) {
        findMethodCallRealTarget(member, classes)
    } else {
        findFieldCallRealTarget(member, classes)
    }
}

fun findMethodCallRealTarget(member: FullyQualifiedMember, classes: Map<Type, ClassNode>): Type? {
    for (name in getSuperTypesAndInterfaces(member.owner.internalName, classes)) {
        val type = Type.getObjectType(name);
        if (type in classes) {
            val node = classes[type]!!
            for (m in node.methods) {
                // skip interface methods that are abstract
                if (node.access and Opcodes.ACC_INTERFACE != 0 && m.access and Opcodes.ACC_ABSTRACT != 0) continue
                // skip privates
                if (m.access and Opcodes.ACC_PRIVATE != 0) continue
                if (m.name == member.name && m.desc == member.type.descriptor) {
                    return type
                }
            }
        } else {
            val c = try {
                Class.forName(name.replace('/', '.'))
            } catch (e: Throwable) {
                continue
            }
            for (m in c.methods) {
                if (m.name == member.name && Type.getMethodDescriptor(m) == member.type.descriptor) {
                    return type
                }
            }
        }
    }
    return null
}

fun findFieldCallRealTarget(member: FullyQualifiedMember, classes: Map<Type, ClassNode>): Type? {
    for (name in getSuperTypes(member.owner.internalName, classes)) {
        val type = Type.getObjectType(name);
        if (type in classes) {
            val node = classes[type]!!
            for (f in node.fields) {
                if (f.name == member.name && f.desc == member.type.descriptor) {
                    return type
                }
            }
        } else {
            val c = try {
                Class.forName(name.replace('/', '.'))
            } catch (e: Throwable) {
                continue
            }
            for (f in c.fields) {
                if (f.name == member.name && Type.getDescriptor(f.type) == member.type.descriptor) {
                    return type
                }
            }
        }
    }
    return null
}

fun main() {
    println(getSuperTypesAndInterfaces("javax/swing/JTable", emptyMap()))
}
