package xyz.wagyourtail.multiversion.split.test

import org.junit.jupiter.api.Test
import xyz.wagyourtail.multiversion.merge.MergeProvider
import xyz.wagyourtail.multiversion.split.SplitProvider
import xyz.wagyourtail.multiversion.test.assertEqual
import xyz.wagyourtail.multiversion.test.simplifiedVisitor
import xyz.wagyourtail.multiversion.util.forEachInZip
import xyz.wagyourtail.multiversion.util.openZipFileSystem
import xyz.wagyourtail.multiversion.util.readClass
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.inputStream

class SplitProviderTest {
    val splitA: Path = Paths.get("./build/libs/test-splitted-a.jar")
    val splitB: Path = Paths.get("./build/libs/test-splitted-b.jar")
    val merged = Paths.get("./build/libs/test-merged.jar")
    val split: Path = Paths.get("./build/libs/test-split.jar")

    val mergedClasses by lazy {
        MergeProvider.classNodesFromJar(merged)
    }

    @Test
    fun testSplitToA() {
        val output = split.resolveSibling("test-result-a.jar")
        SplitProvider.split(split, setOf(merged), "a", output)
        // check if matches splitA
        output.openZipFileSystem().use { fs ->
            splitA.forEachInZip { path, data ->
                if (path.endsWith(".class")) {
                    val expected = data.readClass()
                    val actual = fs.getPath(path).inputStream().readClass()
                    assertEqual(simplifiedVisitor(false) { expected.accept(it) },
                        simplifiedVisitor(false) { actual.accept(it) })
                }
            }
        }
    }
}