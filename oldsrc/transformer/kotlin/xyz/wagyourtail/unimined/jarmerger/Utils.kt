package xyz.wagyourtail.unimined.jarmerger

import java.io.InputStream
import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

fun Path.openZipFileSystem(vararg args: Pair<String, *>): FileSystem = openZipFileSystem(args.toMap())

fun Path.openZipFileSystem(args: Map<String, *> = mapOf<String, Any>()): FileSystem {
    if (!exists() && args["create"] == true) {
        ZipOutputStream(outputStream()).use { stream ->
            stream.closeEntry()
        }
    }
    return FileSystems.newFileSystem(URI.create("jar:${toUri()}"), args, null)
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