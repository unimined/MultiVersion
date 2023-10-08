package xyz.wagyourtail.multiversion.merge

import org.jetbrains.annotations.VisibleForTesting
import org.objectweb.asm.ClassReader
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.SimpleRemapper
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode
import xyz.wagyourtail.multiversion.api.merge.MergeOptions
import xyz.wagyourtail.multiversion.util.*
import xyz.wagyourtail.multiversion.util.asm.getSuperTypes
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.writeBytes

typealias Version = String
typealias MergeName = String
typealias Access = Int
object MergeProvider : MergeOptions {

    fun MethodVisitor.assertionErrorCode() {
        visitCode()
        // throw new AssertionError();
        visitTypeInsn(Opcodes.NEW, "java/lang/AssertionError")
        visitInsn(Opcodes.DUP)
        visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/AssertionError", "<init>", "()V", false)
        visitInsn(Opcodes.ATHROW)
        visitMaxs(2, 1)
    }

    fun classNodesFromJar(jar: Path): Map<Type, ClassNode> {
        val mut = mutableMapOf<Type, ClassNode>()
        jar.forEachInZip { name, stream ->
            if (!name.endsWith(".class")) return@forEachInZip
            val classNode = stream.readClass(ClassReader.SKIP_CODE)
            mut[Type.getObjectType(classNode.name)] = classNode
        }
        return mut
    }

    fun resolveVersions(versionPaths: Map<Version, Path>): Map<Version, Map<Type, ClassNode>> {
        val nodeMaps = mutableMapOf<Version, Map<Type, ClassNode>>()
        for ((version, path) in versionPaths) {
            val nodes = classNodesFromJar(path)
            nodeMaps[version] = nodes
        }
        return nodeMaps
    }

    fun mergeAll(mergable: Map<MergeName, Map<Version, Path>>, outputs: Map<MergeName, Path>) {
        if (outputs.values.all { it.exists() }) {
            return
        }
        val allNodeMaps = mutableMapOf<MergeName, Map<Version, Map<Type, ClassNode>>>()
        for ((name, versions) in mergable) {
            allNodeMaps[name] = resolveVersions(versions)
        }
        val allClasses = allNodeMaps.values.flatMap { it.values.flatMap { it.keys } }.toSet()
        for ((name, nodeMaps) in allNodeMaps) {
            if (!outputs[name]!!.exists()) {
                merge(nodeMaps, allClasses, outputs[name]!!)
            }
        }
    }

    fun merge(nodeMaps: Map<Version, Map<Type, ClassNode>>, allClasses: Set<Type>, output: Path) {
        val allVersionsByClass = nodeMaps.mapValues { it.value.keys }.inverseFlatMulti()
        output.deleteIfExists()
        output.openZipFileSystem(mapOf("create" to "true")).use { fs ->
            for ((className, versions) in allVersionsByClass) {
                val merged = merge(nodeMaps.filter { versions.contains(it.key) }.mapValues { it.value[className]!! }, allVersionsByClass, nodeMaps, allClasses)
                val entry = fs.getPath(merged.name + ".class")
                entry.parent.createDirectories()
                entry.writeBytes(merged.toByteArray(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            }
        }
    }

    private fun appendAccess(accessFlags: Int): String = buildString {
        if (accessFlags and Opcodes.ACC_PUBLIC != 0) {
            append("public ")
        }
        if (accessFlags and Opcodes.ACC_PRIVATE != 0) {
            append("private ")
        }
        if (accessFlags and Opcodes.ACC_PROTECTED != 0) {
            append("protected ")
        }
        if (accessFlags and Opcodes.ACC_FINAL != 0) {
            append("final ")
        }
        if (accessFlags and Opcodes.ACC_STATIC != 0) {
            append("static ")
        }
        if (accessFlags and Opcodes.ACC_SYNCHRONIZED != 0) {
            append("synchronized ")
        }
        if (accessFlags and Opcodes.ACC_VOLATILE != 0) {
            append("volatile ")
        }
        if (accessFlags and Opcodes.ACC_TRANSIENT != 0) {
            append("transient ")
        }
        if (accessFlags and Opcodes.ACC_ABSTRACT != 0) {
            append("abstract ")
        }
        if (accessFlags and Opcodes.ACC_STRICT != 0) {
            append("strictfp ")
        }
        if (accessFlags and Opcodes.ACC_SYNTHETIC != 0) {
            append("synthetic ")
        }
        if (accessFlags and Opcodes.ACC_MANDATED != 0) {
            append("mandated ")
        }
        if (accessFlags and Opcodes.ACC_ENUM != 0) {
            append("enum ")
        }
        if (isNotEmpty()) {
            setLength(length - 1)
        }
    }

    private fun accessByVersion(access: Map<Version, Access>, abstractPriority: Boolean): Pair<Access, Map<Access, Set<Version>>> {
        val nonAbs = listOf(
            access.values.firstOrNull { it and Opcodes.ACC_PUBLIC != 0 },
            access.values.firstOrNull { it and Opcodes.ACC_PROTECTED != 0 },
            access.values.firstOrNull { it and Opcodes.ACC_PUBLIC == 0 && it and Opcodes.ACC_PROTECTED == 0 && it and Opcodes.ACC_PRIVATE == 0 },
            access.values.firstOrNull { it and Opcodes.ACC_PRIVATE != 0 },
        )
        val abs = listOf(
            access.values.firstOrNull { it and Opcodes.ACC_PUBLIC != 0 && it and Opcodes.ACC_ABSTRACT != 0 },
            access.values.firstOrNull { it and Opcodes.ACC_PROTECTED != 0 && it and Opcodes.ACC_ABSTRACT != 0 },
            access.values.firstOrNull { it and Opcodes.ACC_PUBLIC == 0 && it and Opcodes.ACC_PROTECTED == 0 && it and Opcodes.ACC_PRIVATE == 0 && it and Opcodes.ACC_ABSTRACT != 0 },
            access.values.firstOrNull { it and Opcodes.ACC_PRIVATE != 0 && it and Opcodes.ACC_ABSTRACT != 0 }
        )
        val bestAccess = if (abstractPriority) {
            abs.zip(nonAbs)
        } else {
            nonAbs.zip(abs)
        }.flatMap { listOf(it.first, it.second) }.filterNotNull().first()
        return bestAccess to access.inverseMulti()
    }

    @VisibleForTesting
    fun merge(versions: Map<Version, ClassNode>, allVersionsByClass: Map<Type, Set<Version>>, nodeMaps: Map<Version, Map<Type, ClassNode>>, otherMerging: Set<Type>): ClassNode {
        val output = ClassNode()
        // copy class metadata
        output.version = versions.values.minOf { it.version }.coerceAtLeast(52)
        val classAccess = accessByVersion(versions.mapValues { it.value.access }, false)
        output.access = classAccess.first
        output.name = "merged/" + versions.values.first().name
        val classSigsByVersion = versions.mapValues { it.value.signature }.inverseMulti()
        if (classSigsByVersion.size == 1) {
            output.signature = classSigsByVersion.keys.first()
        } else {
            output.signature = null
        }
        // add inner classes
        val innerClasses = versions.flatMap { entry -> entry.value.innerClasses.map { it.name } }
        for (inner in innerClasses) {
            val innerClassNodes = versions.mapValues { it.value.innerClasses.firstOrNull { it.name == inner } }.filterValues { it != null }
            // widest access level
            val access = innerClassNodes.mapValues { it.value!!.access as Access }
            val innerAccess = accessByVersion(access, false).first
            // just grab one for the def
            val innerClass = innerClassNodes.values.first()!!
            output.visitInnerClass(inner, innerClass.outerName, innerClass.innerName, innerAccess)
        }

        val versionsBySuperClasses = versions.mapValues { Type.getObjectType(it.value.superName) }.inverseMulti()
        if (versionsBySuperClasses.size == 1) {
            output.superName = versionsBySuperClasses.keys.first().internalName
            if (allVersionsByClass.contains(versionsBySuperClasses.keys.first())) {
                output.superName = "merged/" + output.superName
            }
        } else {
            // intersect all super types
            val commonSuperTypes = versionsBySuperClasses.flatMap { entry -> entry.value.map { getSuperTypes(entry.key.internalName, nodeMaps[it]!!) } }.reduce { acc, list -> acc.intersect(list.toSet()).toMutableList() }
            output.superName = commonSuperTypes.firstOrNull() ?: "java/lang/Object"
            for ((superName, sVersions) in versionsBySuperClasses) {
                if (superName.internalName == "java/lang/Object") {
                    continue
                }
                output.visitMethod(
                    Opcodes.ACC_PUBLIC,
                    "mv\$castTo\$" + superName.internalName.sanatize(),
                    "()${superName.descriptor}",
                    null,
                    null
                ).apply {
                    assertionErrorCode()
                    visitAnnotation("Lxyz/wagyourtail/multiversion/injected/merge/annotations/MergedMember;", false).apply {
                        visitArray("versions").apply {
                            for (version in sVersions) {
                                visit(null, version)
                            }
                        }.visitEnd()
                        visit("synthetic", true)
                    }.visitEnd()
                }.visitEnd()
            }
        }
        val versionsByInterfaces = versions.mapValues { it.value.interfaces.map { Type.getObjectType(it) }.toSet() }.inverseMulti()
        if (versionsByInterfaces.size == 1) {
            output.interfaces = versionsByInterfaces.keys.first().map { it.internalName }
        } else {
            output.interfaces = versionsByInterfaces.flattenKey().filterValues { it.size == versions.size }.keys.map { it.internalName }
            val interfaces = output.interfaces.map { Type.getObjectType(it) }.toSet()
            for ((iface, iVersions) in versionsByInterfaces.flattenKey()) {
                if (iface in interfaces) {
                    continue
                }
                output.visitMethod(Opcodes.ACC_PUBLIC, "mv\$castTo\$" + iface.internalName.sanatize(), "()${iface.descriptor}", null, null).apply {
                    assertionErrorCode()
                    visitAnnotation(
                        "Lxyz/wagyourtail/multiversion/injected/merge/annotations/MergedMember;",
                        false
                    ).apply {
                        visitArray("versions").apply {
                            for (version in iVersions) {
                                visit(null, version)
                            }
                        }.visitEnd()
                        visit("synthetic", true)
                    }.visitEnd()
                }.visitEnd()
            }
        }
        for (i in 0..output.interfaces.lastIndex) {
            if (allVersionsByClass.contains(Type.getObjectType(output.interfaces[i]))) {
                output.interfaces[i] = "merged/" + output.interfaces[i]
            }
        }
        output.visitAnnotation("Lxyz/wagyourtail/multiversion/injected/merge/annotations/MergedClass;", false).apply {
            visitArray("versions").apply {
                for (version in versions.keys) {
                    visit(null, version)
                }
            }.visitEnd()
            if (classAccess.second.size > 1) {
                visitArray("access").apply {
                    for ((access, aVersions) in classAccess.second) {
                        visitAnnotation(null, "Lxyz/wagyourtail/multiversion/injected/merge/annotations/Access;").apply {
                            visit("value", appendAccess(access))
                            visitArray("versions").apply {
                                for (version in aVersions) {
                                    visit(null, version)
                                }
                            }.visitEnd()
                        }.visitEnd()
                    }
                }.visitEnd()
            }
            val versionsByInheritance = versions.mapValues { Type.getObjectType(it.value.superName) to it.value.interfaces.map { Type.getObjectType(it) }.toSet() }.inverseMulti()
            if (versionsByInheritance.size > 1) {
                visitArray("inheritance").apply {
                    val superName = Type.getObjectType(output.superName)
                    val interfaces = output.interfaces.map { Type.getObjectType(it) }.toSet()
                    for ((inheritance, iVersions) in versionsByInheritance) {
                        if (inheritance.first == superName && inheritance.second == interfaces) {
                            continue
                        }
                        visitAnnotation(null, "Lxyz/wagyourtail/multiversion/injected/merge/annotations/Inheritance;").apply {
                            visitArray("versions").apply {
                                for (version in iVersions) {
                                    visit(null, version)
                                }
                            }.visitEnd()
                            if (inheritance.first != superName) {
                                if (inheritance.first in allVersionsByClass) {
                                    visit("superClass", "merged/" + inheritance.first.internalName)
                                } else {
                                    visit("superClass", inheritance.first.internalName)
                                }
                            }
                            if (inheritance.second != interfaces) {
                                visitArray("interfaces").apply {
                                    for (interfaceName in inheritance.second) {
                                        if (interfaceName in interfaces) {
                                            continue
                                        }
                                        if (interfaceName in allVersionsByClass) {
                                            visit(null, "merged/" + interfaceName.internalName)
                                        } else {
                                            visit(null, interfaceName.internalName)
                                        }
                                    }
                                }.visitEnd()
                            }
                        }.visitEnd()
                    }
                }.visitEnd()
            }
        }.visitEnd()

        val methodsByVersion: Map<Version, Set<Map.Entry<MemberNameAndType, MethodNode>>> = versions.mapValues { entry ->
            entry.value.methods.filter { it.access and Opcodes.ACC_SYNTHETIC == 0 }.associateBy { MemberNameAndType(it.name, Type.getMethodType(it.desc), it.access and Opcodes.ACC_STATIC != 0) }.entries
        }
        val fieldsByVersion: Map<Version, Set<Map.Entry<MemberNameAndType, FieldNode>>> = versions.mapValues { entry ->
            entry.value.fields.filter { it.access and Opcodes.ACC_SYNTHETIC == 0 }.associateBy { MemberNameAndType(it.name, Type.getType(it.desc), it.access and Opcodes.ACC_STATIC != 0) }.entries
        }
        // construct a map of unique methods and fields by the versions they are present in
        val versionsByMethod: Map<MemberNameAndType, Map<Version, MethodNode>> = methodsByVersion.values.flatten().map { it.key }.associateWith { key ->
                methodsByVersion.asSequence().associateNonNull { entry -> entry.value.firstOrNull { it.key == key }?.let { entry.key to it.value } }
            }
        val versionsByField: Map<MemberNameAndType, Map<Version, FieldNode>> = fieldsByVersion.values.flatten().map { it.key }.associateWith { key ->
            fieldsByVersion.asSequence().associateNonNull { entry -> entry.value.firstOrNull { it.key == key }?.let { entry.key to it.value } }
        }
        // check if any methods or fields conflict
        val conflictingMethods: Set<MemberNameAndType> = versionsByMethod.keys.filter { member -> versionsByMethod.keys.any { member.conflicts(it) } }.toSet()
        val conflictingFields: Set<MemberNameAndType> = versionsByField.keys.filter { member -> versionsByField.keys.any { member.conflicts(it) } }.toSet()

        for (member in versionsByMethod.keys) {
            // skip <clinit>
            if (member.name == "<clinit>") {
                continue
            }
            val name = if (conflictingMethods.contains(member)) {
                member.name + "\$mv\$" + versionsByMethod[member]!!.keys.min().sanatize()
            } else {
                member.name
            }
            val memberAccess = accessByVersion(versionsByMethod[member]!!.mapValues { it.value.access }, true)
            val memberSigsByVersion = versionsByMethod[member]!!.mapValues { it.value.signature }.inverseMulti()
            val memberSig = if (memberSigsByVersion.size == 1) {
                memberSigsByVersion.keys.first()
            } else {
                null
            }

            val method = output.visitMethod(
                memberAccess.first,
                name,
                member.type.descriptor,
                memberSig,
                null
            )
            if (memberAccess.first and Opcodes.ACC_ABSTRACT == 0) {
                method.assertionErrorCode()
            }
            method.visitAnnotation("Lxyz/wagyourtail/multiversion/injected/merge/annotations/MergedMember;", false).apply {
                if (conflictingMethods.contains(member)) {
                    visit("name", member.name)
                }
                visitArray("versions").apply {
                    for (version in versionsByMethod[member]!!.keys) {
                        visit(null, version)
                    }
                }.visitEnd()
                if (memberAccess.second.size > 1) {
                    visitArray("access").apply {
                        for ((access, aVersions) in memberAccess.second) {
                            visitAnnotation(null, "Lxyz/wagyourtail/multiversion/injected/merge/annotations/Access;").apply {
                                visit("value", appendAccess(access))
                                visitArray("versions").apply {
                                    for (version in aVersions) {
                                        visit(null, version)
                                    }
                                }.visitEnd()
                            }.visitEnd()
                        }
                    }.visitEnd()
                }
            }.visitEnd()
            method.visitEnd()
        }

        for (member in versionsByField.keys) {
            val name = if (conflictingFields.contains(member)) {
                member.name + "_v_" + versionsByField[member]!!.keys.min().sanatize()
            } else {
                member.name
            }
            val memberAccess = accessByVersion(versionsByField[member]!!.mapValues { it.value.access }, false)
            val memberSigsByVersion = versionsByField[member]!!.mapValues { it.value.signature }.inverseMulti()
            val memberSig = if (memberSigsByVersion.size == 1) {
                memberSigsByVersion.keys.first()
            } else {
                null
            }
            val field = output.visitField(
                memberAccess.first,
                name,
                member.type.descriptor,
                memberSig,
                null
            )
            field.visitAnnotation("Lxyz/wagyourtail/multiversion/injected/merge/annotations/MergedMember;", false).apply {
                if (conflictingMethods.contains(member)) {
                    visit("name", member.name)
                }
                visitArray("versions").apply {
                    for (version in versionsByField[member]!!.keys) {
                        visit(null, version)
                    }
                }.visitEnd()
                if (memberAccess.second.size > 1) {
                    visitArray("access").apply {
                        for ((access, aVersions) in memberAccess.second) {
                            visitAnnotation(null, "Lxyz/wagyourtail/multiversion/injected/merge/annotations/Access;").apply {
                                visit("value", appendAccess(access))
                                visitArray("versions").apply {
                                    for (version in aVersions) {
                                        visit(null, version)
                                    }
                                }.visitEnd()
                            }.visitEnd()
                        }
                    }.visitEnd()
                }
            }.visitEnd()
            field.visitEnd()
        }
        val remapped = ClassNode()
        val remapper = ClassRemapper(remapped, SimpleRemapper(allVersionsByClass.keys.map { it.internalName }.associateWith { "merged/$it" } + otherMerging.map { it.internalName }.associateWith { "merged/$it" }))
        output.accept(remapper)
        return remapped
    }

    data class MemberNameAndType(val name: String, val type: Type, val static: Boolean) {
        fun conflicts(other: MemberNameAndType): Boolean {
            if (name == other.name && type == other.type && static != other.static) {
                return true
            }
            if (type.sort == Type.METHOD) {
                if (name == other.name) {
                    val ret = type.returnType
                    val otherRet = other.type.returnType
                    return ret != otherRet
                }
                return false
            } else {
                return name == other.name && type != other.type
            }
        }
    }
}