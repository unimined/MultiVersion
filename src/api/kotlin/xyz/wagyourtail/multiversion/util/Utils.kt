package xyz.wagyourtail.multiversion.util

import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.LinkedList
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream


infix fun <T> List<T>.step(count: Int): List<T> = step(0, count)
fun <T> List<T>.step(start: Int, count: Int): List<T> = slice(start..lastIndex step count)

fun <T> List<T>.step(start: Int, end: Int, count: Int): List<T> = slice(start until end step count)

fun InputStream.readClass(parsing: Int = 0): ClassNode {
    val node = ClassNode()
    val classReader = ClassReader(this)
    classReader.accept(node, parsing)
    return node
}

fun String.toInternalName() =
    if (startsWith("L") && endsWith(";")) {
        substring(1, length - 1)
    } else {
        this
    }

fun String.sanatize(): String {
    return replace(Regex("[^a-zA-Z0-9_]"), "_")
}

@Suppress("UNCHECKED_CAST")
fun <K, V> Map<K, V?>.nonNullValues(): Map<K, V> = filterValues { it != null } as Map<K, V>

@Suppress("UNCHECKED_CAST")
inline fun <reified T> List<*>.checkedCast() = onEach { it as T } as List<T>


fun Configuration.getFile(dep: Dependency, extension: Regex): File {
    resolve()
    return files(dep).first { it.extension.matches(extension) }
}

fun Configuration.getFile(dep: Dependency, extension: String = "jar"): File {
    resolve()
    return files(dep).first { it.extension == extension }
}
fun Path.getSha1(): String {
    val digestSha1 = MessageDigest.getInstance("SHA-1")
    inputStream().use {
        digestSha1.update(it.readBytes())
    }
    val hashBytes = digestSha1.digest()
    return hashBytes.joinToString("") { String.format("%02x", it) }
}

fun File.getSha1() = toPath().getSha1()

fun Path.getShortSha1(): String = getSha1().substring(0, 7)

fun File.getShortSha1() = toPath().getShortSha1()

fun Path.deleteRecursively() {
    Files.walkFileTree(this, object: SimpleFileVisitor<Path>() {
        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            dir.deleteExisting()
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            file.deleteExisting()
            return FileVisitResult.CONTINUE
        }
    })
}

fun Path.forEachFile(action: (Path) -> Unit) {
    Files.walkFileTree(this, object: SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            action(file)
            return FileVisitResult.CONTINUE
        }
    })
}

fun ByteArray.toHex() = joinToString(separator = "") { byte -> "%02x".format(byte) }


fun Path.isZip(): Boolean =
    inputStream().use { stream -> ByteArray(4).also { stream.read(it, 0, 4) } }
        .contentEquals(byteArrayOf(0x50, 0x4B, 0x03, 0x04))

fun Path.readZipContents(): List<String> {
    val contents = mutableListOf<String>()
    forEachInZip { entry, _ ->
        contents.add(entry)
    }
    return contents
}

fun <E, K, V> Iterable<E>.associateNonNull(apply: (E) -> Pair<K, V>?): Map<K, V> {
    val mut = mutableMapOf<K, V>()
    for (e in this) {
        apply(e)?.let {
            mut.put(it.first, it.second)
        }
    }
    return mut
}

fun <E, K, V> Sequence<E>.associateNonNull(apply: (E) -> Pair<K, V>?): Map<K, V> {
    val mut = mutableMapOf<K, V>()
    for (e in this) {
        apply(e)?.let {
            mut.put(it.first, it.second)
        }
    }
    return mut
}

fun Path.forEachInZip(action: (String, InputStream) -> Unit) {
    ZipInputStream(inputStream()).use { stream ->
        var entry = stream.nextEntry
        while (entry != null) {
            if (entry.isDirectory) {
                entry = stream.nextEntry
                continue
            }
            action(entry.name, stream)
            entry = stream.nextEntry
        }
    }
}

fun <T> Path.readZipInputStreamFor(path: String, throwIfMissing: Boolean = true, action: (InputStream) -> T): T {
    ZipInputStream(inputStream()).use { stream ->
        var entry = stream.nextEntry
        while (entry != null) {
            if (entry.isDirectory) {
                entry = stream.nextEntry
                continue
            }
            if (entry.name == path) {
                return action(stream)
            }
            entry = stream.nextEntry
        }
    }
    if (throwIfMissing) {
        throw IllegalArgumentException("Missing file $path in $this")
    }
    @Suppress("UNCHECKED_CAST")
    return null as T
}

fun Path.openZipFileSystem(args: Map<String, *> = mapOf<String, Any>()): FileSystem {
    if (!exists() && args["create"] == true) {
        ZipOutputStream(outputStream()).use { stream ->
            stream.closeEntry()
        }
    }
    return FileSystems.newFileSystem(URI.create("jar:${toUri()}"), args, null)
}

fun ClassNode.toByteArray(): ByteArray {
    val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
    accept(writer)
    return writer.toByteArray()
}

fun <K, V> Map<K, V>.inverseMulti(): Map<V, Set<K>> {
    val result = defaultedMapOf<V, MutableSet<K>> { mutableSetOf() }
    for ((k, v) in this) {
        result[v].add(k)
    }
    return result.map
}

fun <K, V> Map<K, Iterable<V>>.inverseFlatMulti(): Map<V, Set<K>> {
    val result = defaultedMapOf<V, MutableSet<K>> { mutableSetOf() }
    for ((k, v) in this) {
        for (v2 in v) {
            result[v2].add(k)
        }
    }
    return result.map
}

fun <K, V, E: Iterable<K>> Map<E, V>.flattenKey(): Map<K, V> {
    val result = mutableMapOf<K, V>()
    for ((k, v) in this) {
        for (k2 in k) {
            result[k2] = v
        }
    }
    return result
}