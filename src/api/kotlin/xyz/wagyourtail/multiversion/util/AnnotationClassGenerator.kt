package xyz.wagyourtail.multiversion.util

import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import java.lang.reflect.Proxy

object AnnotationClassGenerator {

    private val annotationClasses: MutableMap<Class<out Annotation>, Class<out Annotation>> = mutableMapOf()

    fun <T> List<T>.step(count: Int): List<T> = step(0, count)
    fun <T> List<T>.step(start: Int, count: Int): List<T> = slice(start..lastIndex step count)

    fun <T: Annotation> fromNode(node: AnnotationNode): T {
        val clazz: Class<T> = Class.forName(node.desc.replace("/", ".")) as Class<T>
//        val constructable = if (clazz in annotationClasses) {
//            annotationClasses[clazz] as Class<out T>
//        } else {
//            createAnnotationClass(clazz)
//        }
    val values = (node.values.step(2).checkedCast<String>()).zip(node.values.step(1, 2)).associate {
            val second = it.second
            it.first to if (second is AnnotationNode) {
                fromNode(it.second as AnnotationNode)
            } else if (second is Array<*> && second.isArrayOf<String>()){
                TODO()
            } else if (second is Type) {
                TODO()
            } else {
                second
            }
        }
        return Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz)) { _, method, _ ->
            values.getOrElse(method.name) { method.defaultValue }
        } as T
    }

//    fun <T: Annotation> toNode(annotation: T, visitor: AnnotationVisitor) {
//        val clazz = annotation.annotationClass.java
//        val values = clazz.declaredMethods.associate {
//            it.name to it.invoke(annotation)
//        }
//        visitor.visit(clazz.name.replace(".", "/"), values)
//    }
//
//
//    fun <T: Annotation> createAnnotationClass(clazz: Class<T>): Class<out T> {
//        val classBuilder = ClassNode()
//        classBuilder.visit(52, Opcodes.ACC_PUBLIC, clazz.name + "Impl", null, "java/lang/Object", arrayOf(clazz.name))
//        for (declaredMethod in clazz.declaredMethods) {
//            val fieldBuilder = classBuilder.visitField(Opcodes.ACC_PRIVATE, declaredMethod.name, Type.getDescriptor(declaredMethod.returnType), null, null)
//            fieldBuilder.visitEnd()
//            val methodBuilder = classBuilder.visitMethod(Opcodes.ACC_PUBLIC, declaredMethod.name, Type.getMethodDescriptor(declaredMethod), null, null)
//            methodBuilder.visitCode()
//            methodBuilder.visitVarInsn(Opcodes.ALOAD, 0)
//            methodBuilder.visitFieldInsn(Opcodes.GETFIELD, clazz.name + "Impl", declaredMethod.name, Type.getDescriptor(declaredMethod.returnType))
//            methodBuilder.visitInsn(Opcodes.ARETURN)
//            methodBuilder.visitMaxs(0, 0)
//            methodBuilder.visitEnd()
//        }
//        classBuilder.visitEnd()
//        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
//        classBuilder.accept(writer)
//        val arr = writer.toByteArray()
//        val impl = defineClass(clazz.name + "Impl", arr, 0, arr.size)
//        annotationClasses[clazz] = impl as Class<out Annotation>
//        return impl as Class<out T>
//    }

}