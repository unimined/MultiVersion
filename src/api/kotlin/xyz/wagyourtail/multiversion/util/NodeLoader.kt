package xyz.wagyourtail.multiversion.util

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.*

object NodeLoader : ClassLoader(NodeLoader::class.java.classLoader) {

    val classVersion by lazy {
        val version = System.getProperty("java.class.version")
        if (version == null) {
            System.err.println("Could not get java.class.version")
            52
        } else {
            version.toFloat().toInt()
        }
    }

    fun listToArray(list: List<*>, type: Class<*>): Any {
        val arr = java.lang.reflect.Array.newInstance(type, list.size)
        list.forEachIndexed { i, v ->
            val value = if (v is AnnotationNode) {
                fromNode(v)
            } else if (v is List<*>) {
                listToArray(v, type.componentType)
            } else if (v is Type) {
                TODO("Class not implemented")
            } else if (v is Array<*> && v.isArrayOf<String>()) {
                TODO("Enum not implemented")
            } else {
                v
            }
            java.lang.reflect.Array.set(arr, i, value)
        }
        return arr
    }

    fun <T: Annotation> fromNode(node: AnnotationNode): T {
        val clazz: Class<T> = Class.forName(Type.getType(node.desc).internalName.replace("/", ".")) as Class<T>
        val values = (node.values.step(2).checkedCast<String>()).zip(node.values.step(1, 2)).associate {
            val second = it.second
            it.first to if (second is AnnotationNode) {
                fromNode(it.second as AnnotationNode)
            } else if (second is Array<*> && second.isArrayOf<String>()){
                TODO("Enum not implemented")
            } else if (second is Type) {
                TODO("Class not implemented")
            } else if (second is List<*>) {
                // get type that's supposed to be in list from the annotation class
                val type = clazz.getDeclaredMethod(it.first).returnType.componentType
                listToArray(second, type)
            } else {
                second
            }
        }
        return Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz)) { _, method, _ ->
            values.getOrElse(method.name) { method.defaultValue }
        } as T
    }

    val classLookup = mutableMapOf<String, Class<*>>()

    fun fromNode(owner: String, mNode: MethodNode): Method {
        if (mNode.access and Opcodes.ACC_STATIC == 0) throw IllegalArgumentException("node must be static")
        val cname = "xyz/wagyourtail/multiversion/util/NodeLoader\$${owner.sanatize()}\$${mNode.name}"
        val methodType = Type.getMethodType(mNode.desc)
        val parameterTypes = methodType.argumentTypes
        val returnType = methodType.returnType
        val clazz = synchronized(getClassLoadingLock(cname)) {
            classLookup.getOrPut(cname) {
                val node = ClassNode()
                node.name = cname
                node.superName = "java/lang/Object"
                node.version = classVersion
                node.access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL
                node.methods.add(MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                    visitCode()
                    visitVarInsn(Opcodes.ALOAD, 0)
                    visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                    visitInsn(Opcodes.RETURN)
                    visitMaxs(0, 0)
                    visitEnd()
                })
                node.methods.add(
                    MethodNode(
                        Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                        mNode.name,
                        mNode.desc,
                        null,
                        null
                    ).apply {
                        instructions = mNode.instructions
                        visitMaxs(0, 0)
                        visitEnd()
                    })
                val writer = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
                node.accept(writer)
                val arr = writer.toByteArray()
                val c = defineClass(node.name.replace("/", "."), arr, 0, arr.size)
                resolveClass(c)
                c
            }
        }
        val paramClasses = parameterTypes.map {
            if (it.sort == Type.OBJECT) {
                Class.forName(it.className)
            } else {
                // is primitive
                when (it.sort) {
                    Type.BOOLEAN -> Boolean::class.javaPrimitiveType
                    Type.BYTE -> Byte::class.javaPrimitiveType
                    Type.CHAR -> Char::class.javaPrimitiveType
                    Type.DOUBLE -> Double::class.javaPrimitiveType
                    Type.FLOAT -> Float::class.javaPrimitiveType
                    Type.INT -> Int::class.javaPrimitiveType
                    Type.LONG -> Long::class.javaPrimitiveType
                    Type.SHORT -> Short::class.javaPrimitiveType
                    else -> throw IllegalArgumentException("unknown primitive type ${it.sort}")
                }
            }
        }.toTypedArray()
        return clazz.getDeclaredMethod(mNode.name, *paramClasses)
    }


}

fun main() {
    val mNode = MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "test", "()V", null, null).apply {
        visitCode()
        // System.out.println("hello world");
        visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;")
        visitLdcInsn("hello world")
        visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false)
        visitInsn(Opcodes.RETURN)
        visitMaxs(0, 0)
        visitEnd()
    }
    val method = NodeLoader.fromNode("xyz/wagyourtail/multiversion/util/NodeLoader", mNode)
    method.invoke(null)
}