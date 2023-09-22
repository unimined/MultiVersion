package xyz.wagyourtail.unimined.jarmerger.glue

import xyz.wagyourtail.unimined.jarmerger.GlueGun
import java.nio.file.Path

class GlueStick(val name: String, val jars: Set<GlueGun.JarInfo>, val dependencies: Map<GlueGun.JarInfo, GlueGun.MergedJarInfo>, val workingFolder: Path) {

}