package xyz.wagyourtail.multiversion.test

import org.objectweb.asm.*
import org.objectweb.asm.util.ASMifier
import org.objectweb.asm.util.Textifier
import org.objectweb.asm.util.TraceClassVisitor
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals


fun simplifiedVisitor(visitor: (ClassVisitor) -> Unit): (ClassVisitor) -> Unit = simplifiedVisitor(true, visitor)
fun simplifiedVisitor(skipCode: Boolean, visitor: (ClassVisitor) -> Unit): (ClassVisitor) -> Unit = {
    visitor(object : ClassVisitor(Opcodes.ASM9, it) {
        override fun visitMethod(
            access: Int,
            name: String?,
            descriptor: String?,
            signature: String?,
            exceptions: Array<out String>?
        ): MethodVisitor {
            val superVisitor = super.visitMethod(access, name, descriptor, signature, exceptions)
            return object: MethodVisitor(Opcodes.ASM9, if (skipCode) null else superVisitor) {
                override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
                    return superVisitor.visitAnnotation(descriptor, visible)
                }

                override fun visitLineNumber(line: Int, start: Label?) {
                }
            }
        }

        override fun visitSource(source: String?, debug: String?) {

        }
    })
}

fun classToTextify(visitor: (ClassVisitor) -> Unit): String {
    val baos = ByteArrayOutputStream()
    val pw = PrintWriter(baos)
    val traceClassVisitor = TraceClassVisitor(null, Textifier(), pw)
    visitor(traceClassVisitor)
    return baos.toString()
}

fun classToAsmify(visitor: (ClassVisitor) -> Unit): String {
    val baos = ByteArrayOutputStream()
    val pw = PrintWriter(baos)
    val traceClassVisitor = TraceClassVisitor(null, ASMifier(), pw)
    visitor(traceClassVisitor)
    return baos.toString()
}

fun writeClass(visitor: (ClassVisitor) -> Unit, path: Path) {
    val baos = ByteArrayOutputStream()
    val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
    visitor(cw)
    path.parent.createDirectories()
    path.writeBytes(cw.toByteArray())
}

fun assertEqual(expected: (ClassVisitor) -> Unit, actual: (ClassVisitor) -> Unit) {
    val expectedStr = classToTextify(expected)
    val actualStr = classToTextify(actual)
    assertEquals(expectedStr, actualStr)
    val expectedStr2 = classToAsmify(expected)
    val actualStr2 = classToAsmify(actual)
    assertEquals(expectedStr2, actualStr2)
}

