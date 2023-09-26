package xyz.wagyourtail.multiversion.merge.test

import org.junit.jupiter.api.Test
import org.objectweb.asm.*
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.util.ASMifier
import org.objectweb.asm.util.Textifier
import org.objectweb.asm.util.TraceClassVisitor
import xyz.wagyourtail.multiversion.merge.MergeProvider
import xyz.wagyourtail.multiversion.util.readZipInputStreamFor
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals

class MergeProviderTest {

    private fun simplifiedVisitor(visitor: (ClassVisitor) -> Unit): (ClassVisitor) -> Unit = {
        visitor(object : ClassVisitor(Opcodes.ASM9, it) {
            override fun visitMethod(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor {
                val superVisitor = super.visitMethod(access, name, descriptor, signature, exceptions)
                return object: MethodVisitor(Opcodes.ASM9) {
                    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
                        return superVisitor.visitAnnotation(descriptor, visible)
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
        val expectedStr = classToTextify(simplifiedVisitor(expected))
        val actualStr = classToTextify(simplifiedVisitor(actual))
        assertEquals(expectedStr, actualStr)
        val expectedStr2 = classToAsmify(simplifiedVisitor(expected))
        val actualStr2 = classToAsmify(simplifiedVisitor(actual))
        assertEquals(expectedStr2, actualStr2)
    }

    val mergeA: Path = Paths.get("./build/libs/test-merge-a.jar")
    val mergeB: Path = Paths.get("./build/libs/test-merge-b.jar")
    val merged = Paths.get("./build/libs/test-merged.jar")

    fun classNodeFromJar(jar: Path, path: String): ClassNode {
        val classNode = ClassNode()
        val classReader = jar.readZipInputStreamFor(path) { ClassReader(it) }
        classReader.accept(classNode, ClassReader.SKIP_CODE)
        return classNode
    }

    fun test(name: String) {
        val a = classNodeFromJar(mergeA, "$name.class")
        val b = classNodeFromJar(mergeB, "$name.class")
        val merged = classNodeFromJar(merged, "merged/$name.class")
        val out = MergeProvider().merge(mapOf("a" to a, "b" to b), setOf("com/example/ClassA", "com/example/ClassB", "com/example/ClassC"))
        assertEqual({ merged.accept(it) }, { out.accept(it) })
        writeClass({ out.accept(it) }, Paths.get("./build/tmp/test/merged/$name.class"))
    }

    @Test
    fun classA() {
        test("com/example/ClassA")
    }

    @Test
    fun classB() {
        test("com/example/ClassB")
    }

    @Test
    fun classC() {
        test("com/example/ClassC")
    }

}