package xyz.wagyourtail.multiversion.merge.test

import org.junit.jupiter.api.Test
import org.objectweb.asm.*
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.util.ASMifier
import org.objectweb.asm.util.Textifier
import org.objectweb.asm.util.TraceClassVisitor
import xyz.wagyourtail.multiversion.merge.MergeProvider
import xyz.wagyourtail.multiversion.test.assertEqual
import xyz.wagyourtail.multiversion.test.simplifiedVisitor
import xyz.wagyourtail.multiversion.test.writeClass
import xyz.wagyourtail.multiversion.util.forEachInZip
import xyz.wagyourtail.multiversion.util.readClass
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

    val mergeA: Path = Paths.get("./build/libs/test-merge-a.jar")
    val mergeB: Path = Paths.get("./build/libs/test-merge-b.jar")
    val merged = Paths.get("./build/libs/test-merged.jar")

    val mergedClasses by lazy {
        MergeProvider.classNodesFromJar(merged)
    }

    val resolved by lazy {
        MergeProvider.resolveVersions(mapOf("a" to mergeA, "b" to mergeB))
    }

    fun test(name: String) = test(Type.getObjectType(name))

    fun test(name: Type) {
        val (allVersionsByClass, nodeMaps) = resolved
        val out = MergeProvider.merge(allVersionsByClass[name]!!.associateWith { nodeMaps[it]!![name]!! }, allVersionsByClass, nodeMaps)
        assertEqual(simplifiedVisitor { mergedClasses[Type.getObjectType("merged/" + name.internalName)]!!.accept(it) }, simplifiedVisitor { out.accept(it) })
//        writeClass({ out.accept(it) }, Paths.get("./build/tmp/test/merged/$name.class"))
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