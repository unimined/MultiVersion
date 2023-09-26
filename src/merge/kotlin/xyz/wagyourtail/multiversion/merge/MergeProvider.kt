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
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.writeBytes

typealias Version = String
typealias Class = String
typealias Access = Int
class MergeProvider : MergeOptions {

    fun String.sanatize(): String {
        return replace(Regex("[^a-zA-Z0-9_]"), "_")
    }

    fun MethodVisitor.assertionErrorCode() {
        visitCode()
        // throw new AssertionError();
        visitTypeInsn(Opcodes.NEW, "java/lang/AssertionError")
        visitInsn(Opcodes.DUP)
        visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/AssertionError", "<init>", "()V", false)
        visitInsn(Opcodes.ATHROW)
        visitMaxs(2, 1)
    }

    fun merge(versions: Map<Version, Path>, output: Path) {
        val classesByVersion = versions.mapValues { entry ->
            entry.value.readZipContents().filter { it.endsWith(".class") }.map { it as Class }.toSet()
        }
        val allClassesByVersions = classesByVersion.values.flatten().toSet().associateWith { className ->
            classesByVersion.filter { it.value.contains(className) }.keys
        }
        output.openZipFileSystem(mapOf("create" to "true")).use { fs ->
            for ((className, v) in allClassesByVersions) {
                val nodeMap = v.associateWith { version ->
                    versions[version]!!.readZipInputStreamFor(className) {
                        val reader = ClassReader(it)
                        val node = ClassNode()
                        reader.accept(node, ClassReader.SKIP_CODE)
                        node
                    }
                }
                val merged = merge(nodeMap, allClassesByVersions.keys)
                val entry = fs.getPath(className)
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
    fun merge(versions: Map<Version, ClassNode>, mergingClasses: Set<Class> = setOf()): ClassNode {
        val output = ClassNode()
        // copy class metadata
        output.version = versions.values.minOf { it.version }.coerceAtLeast(52)
        val classAccess = accessByVersion(versions.mapValues { it.value.access }, false)
        output.access = classAccess.first
        output.name = "merged/" + versions.values.first().name
        val classSigsByVersion = versions.mapValues { it.value.signature }.filterValues { it != null }.inverseMulti()
        if (classSigsByVersion.size == 1) {
            output.signature = classSigsByVersion.keys.first()
        }
        val versionsBySuperClasses = versions.mapValues { it.value.superName as Class }.inverseMulti()
        if (versionsBySuperClasses.size == 1) {
            output.superName = versionsBySuperClasses.keys.first()
            if (mergingClasses.contains(output.superName)) {
                output.superName = "merged/" + output.superName
            }
        } else {
            output.superName = "java/lang/Object"
            for ((superName, sVersions) in versionsBySuperClasses) {
                if (superName == "java/lang/Object") {
                    continue
                }
                val castMethod = output.visitMethod(
                    Opcodes.ACC_PUBLIC,
                    "mv\$castTo\$" + superName.sanatize(),
                    "()L$superName;",
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
                    visitEnd()
                }
            }
        }
        val versionsByInterfaces = versions.mapValues { it.value.interfaces.toSet() as Set<Class> }.inverseMulti()
        if (versionsByInterfaces.size == 1) {
            output.interfaces = versionsByInterfaces.keys.first().toList()
        } else {
            output.interfaces = versionsByInterfaces.flattenKey().filterValues { it.size == versions.size }.keys.toList()
            val interfaces = output.interfaces.toSet()
            for ((iface, iVersions) in versionsByInterfaces.flattenKey()) {
                if (iface in interfaces) {
                    continue
                }
                val castMethod = output.visitMethod(Opcodes.ACC_PUBLIC, "mv\$castTo\$" + iface.sanatize(), "()L$iface;", null, null)
                castMethod.visitCode()
                // throw new AssertionError();
                castMethod.visitTypeInsn(Opcodes.NEW, "java/lang/AssertionError")
                castMethod.visitInsn(Opcodes.DUP)
                castMethod.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/AssertionError", "<init>", "()V", false)
                castMethod.visitInsn(Opcodes.ATHROW)
                castMethod.visitMaxs(2, 1)
                castMethod.visitAnnotation("Lxyz/wagyourtail/multiversion/injected/merge/annotations/MergedMember;", false).apply {
                    visitArray("versions").apply {
                        for (version in iVersions) {
                            visit(null, version)
                        }
                    }.visitEnd()
                    visit("synthetic", true)
                }.visitEnd()
                castMethod.visitEnd()
            }
        }
        for (i in 0..output.interfaces.lastIndex) {
            if (mergingClasses.contains(output.interfaces[i])) {
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
            val versionsByInheritance = versions.mapValues { it.value.superName as Class to it.value.interfaces.toSet() as Set<Class> }.inverseMulti()
            if (versionsByInheritance.size > 1) {
                visitArray("inheritance").apply {
                    val superName = output.superName
                    val interfaces = output.interfaces.toSet()
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
                                visit("superClass", Type.getObjectType(inheritance.first))
                            }
                            if (inheritance.second != interfaces) {
                                visitArray("interfaces").apply {
                                    for (interfaceName in inheritance.second) {
                                        if (interfaceName in interfaces) {
                                            continue
                                        }
                                        visit(null, Type.getObjectType(interfaceName))
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
            val memberSigsByVersion = versionsByMethod[member]!!.mapValues { it.value.signature }.filterValues { it != null }.inverseMulti()
            val memberSig = if (memberSigsByVersion.size == 1) {
                classSigsByVersion.keys.first()
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
            val memberSigsByVersion = versionsByField[member]!!.mapValues { it.value.signature }.filterValues { it != null }.inverseMulti()
            val memberSig = if (memberSigsByVersion.size == 1) {
                classSigsByVersion.keys.first()
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
        val remapper = ClassRemapper(remapped, SimpleRemapper(mergingClasses.associateWith { "merged/$it" }))
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