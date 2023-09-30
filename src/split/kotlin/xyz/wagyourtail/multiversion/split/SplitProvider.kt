package xyz.wagyourtail.multiversion.split

import org.jetbrains.annotations.VisibleForTesting
import org.objectweb.asm.*
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.commons.SignatureRemapper
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor
import org.objectweb.asm.signature.SignatureWriter
import org.objectweb.asm.tree.*
import xyz.wagyourtail.multiversion.api.split.SplitOptions
import xyz.wagyourtail.multiversion.injected.merge.annotations.MergedClass
import xyz.wagyourtail.multiversion.injected.merge.annotations.MergedMember
import xyz.wagyourtail.multiversion.injected.split.annotations.*
import xyz.wagyourtail.multiversion.util.NodeLoader
import xyz.wagyourtail.multiversion.util.asm.*
import xyz.wagyourtail.multiversion.util.inverseMulti
import xyz.wagyourtail.multiversion.util.openZipFileSystem
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.writeBytes

typealias Version = String
@Suppress("MemberVisibilityCanBePrivate")
object SplitProvider : SplitOptions {

    fun split(input: Path, mergedClasspath: Set<Path>, target: Version, output: Path) {
        split(AnnotationDiscovery(input, mergedClasspath).apply { init() }, target, output)
    }

    fun split(discovery: AnnotationDiscovery, target: Version, output: Path) {
        // stage 2: apply changes to classes, (plus remove/versioned)
        val transformed = strip(discovery, target)
        // stage 3: run over file and see if any bad version accesses remain
        // write to temp file first
        val temp = output.resolveSibling("${output.nameWithoutExtension}.strip.jar")
        write(transformed, discovery.mergedData, temp, target)
        SplitVerifier.verify(discovery, transformed, target)
        // stage 3: remap to use non-merged classes
        val remapped = remap(discovery, transformed)
        // stage 4: write classes to output
        write(remapped, discovery.mergedData, output, target)
    }

    fun strip(discovery: AnnotationDiscovery, target: Version): Map<Type, ClassNode> {
        val transformed = mutableMapOf<Type, ClassNode>()
        for ((type, node) in discovery.inputClasses) {
            if (strip(node, target)) {
                discovery.inputClasses.remove(type)
            }
            transformed[type] = stub(discovery, node, target)
//            transformed[type] = node
        }
        return transformed
    }

    @VisibleForTesting
    fun strip(node: ClassNode, target: Version): Boolean {
        // class
        if (shouldStrip(node.invisibleAnnotations, target)) {
            return true
        }
        val annotations = node.invisibleTypeAnnotations.map { TypeReference(it.typeRef) to it }.filter { it.first.sort == TypeReference.CLASS_EXTENDS }
        val extends = annotations.filter { it.first.superTypeIndex == -1 }.map { it.second }
        val implements = annotations.filter { it.first.superTypeIndex != -1 }.associate { it.second to node.interfaces[it.first.superTypeIndex] }.inverseMulti()
        node.invisibleTypeAnnotations = mutableListOf()
        // superType
        if (shouldStrip(extends, target)) {
            // replace super call
            for (method in node.methods) {
                if (method.name == "<init>") {
                    val instructions = method.instructions
                    var skip = 0
                    for (insn in instructions) {
                        if (insn.opcode == Opcodes.NEW && insn is TypeInsnNode && insn.desc == node.superName) {
                            skip++
                        } else if (insn.opcode == Opcodes.INVOKESPECIAL && insn is MethodInsnNode && (insn.owner == node.superName || insn.owner == node.name) && insn.name == "<init>") {
                            skip--
                            if (skip < 0) {
                                if (insn.owner == node.superName) {
                                    if (insn.desc != "()V") {
                                        throw IllegalStateException("Expected <init> call to super to have no arguments")
                                    }
                                    insn.owner = "java/lang/Object"
                                }
                                break
                            }
                        }
                    }
                }
            }
            node.superName = "java/lang/Object"
        } else {
            node.invisibleAnnotations.addAll(extends)
        }
        // interfaces
        node.interfaces = mutableListOf()
        for (type in node.interfaces.toList()) {
            if (!shouldStrip(implements[type], target)) {
                node.invisibleTypeAnnotations.addAll(implements[type]?.onEach { it.typeRef = TypeReference.newSuperTypeReference(node.interfaces.size).value } ?: emptyList())
                node.interfaces.add(type)
            }
        }
        // inner-class
        for (innerClass in node.innerClasses.toList()) {
            if (Type.getObjectType(innerClass.name) in listOf(
                    Type.getType(Replace::class.java),
                    Type.getType(Replace.ReplaceHolder::class.java)
            )) {
                node.innerClasses.remove(innerClass)
            }
        }
        // methods
        for (method in node.methods.toList()) {
            if (shouldStrip(method.invisibleAnnotations, target)) {
                node.methods.remove(method)
            }
        }
        // fields
        for (field in node.fields.toList()) {
            if (shouldStrip(field.invisibleAnnotations, target)) {
                node.fields.remove(field)
            }
        }
        return false
    }

    val stripConditions = mapOf<Type, (AnnotationNode, String) -> Boolean>(
        Type.getType(Stub::class.java) to { node, target ->
            val stub = NodeLoader.fromNode<Stub>(node)
            // if stub does not contain target then strip
            !stub.versions.contains(target)
        },
        Type.getType(Stub.StubHolder::class.java) to { node, target ->
            val stubHolder = NodeLoader.fromNode<Stub.StubHolder>(node)
            // if all stubs do not contain target then strip
            !stubHolder.value.any { it.versions.contains(target) }
        },
        Type.getType(Replace::class.java) to { node, target ->
            val replace = NodeLoader.fromNode<Replace>(node)
            // if replace contains target then strip
            replace.versions.contains(target)

        },
        Type.getType(Replace.ReplaceHolder::class.java) to { node, target ->
            val replaceHolder = NodeLoader.fromNode<Replace.ReplaceHolder>(node)
            // if any replace contains target then strip
            replaceHolder.value.any { it.versions.contains(target) }
        },
        Type.getType(Modify::class.java) to { node, target ->
            // always strip, these are "compile-time" asm transforms
            true
        },
        Type.getType(Modify.ModifyHolder::class.java) to { node, target ->
            // always strip, these are "compile-time" asm transforms
            true
        },
        Type.getType(Remove::class.java) to { node, target ->
            val remove = NodeLoader.fromNode<Remove>(node)
            // if remove contains target then strip
            remove.versions.contains(target)
        },
        Type.getType(Versioned::class.java) to { node, target ->
            val versioned = NodeLoader.fromNode<Versioned>(node)
            // if versioned does not contain target then strip
            !versioned.versions.contains(target)
        },
    )

    private fun shouldStrip(annotations: Iterable<AnnotationNode>?, target: Version): Boolean {
        if (annotations == null) return false
        for (annotation in annotations) {
            val type = Type.getType(annotation.desc)
            if (type in stripConditions) {
                return stripConditions[type]!!(annotation, target)
            }
        }
        return false
    }

    fun stub(discovery: AnnotationDiscovery, node: ClassNode, target: Version): ClassNode {
        val sc = stubClasses(discovery, node, target)
        val sm = stubMembers(discovery, sc, target)
        return sm
    }

    fun stubClasses(discovery: AnnotationDiscovery, node: ClassNode, target: Version): ClassNode {
        // super
        node.superName = stubType(discovery, Type.getObjectType(node.superName), target).internalName

        // interface
        for ((index, type) in node.interfaces.withIndex()) {
            node.interfaces[index] = stubType(discovery, Type.getObjectType(type), target).internalName
        }

        //sig
        if (node.signature != null) {
            node.signature = transformSignature(discovery, node.signature, target)
        }

        // field types
        for (field in node.fields) {
            field.desc = stubType(discovery, Type.getType(field.desc), target).descriptor
            if (field.signature != null) {
                field.signature = transformSignature(discovery, field.signature, target)
            }
        }

        // method types
        for (method in node.methods) {
            method.desc = stubType(discovery, Type.getMethodType(method.desc), target).descriptor

            if (method.signature != null) {
                method.signature = transformSignature(discovery, method.signature, target)
            }

            // method content
            val instructions = method.instructions
            for (insn in instructions) {
                if (insn is MethodInsnNode) {
                    insn.owner = stubType(discovery, Type.getObjectType(insn.owner), target).internalName
                    insn.desc = stubType(discovery, Type.getMethodType(insn.desc), target).descriptor
                } else if (insn is FieldInsnNode) {
                    insn.owner = stubType(discovery, Type.getObjectType(insn.owner), target).internalName
                    insn.desc = stubType(discovery, Type.getType(insn.desc), target).descriptor
                    val insnDesc = Type.getType(insn.desc)
                    if (discovery.classStubs.contains(insnDesc)) {
                        insn.desc = discovery.classStubs[insnDesc][target]?.first?.descriptor ?: insn.desc
                    }
                } else if (insn is TypeInsnNode) {
                    insn.desc = stubType(discovery, Type.getObjectType(insn.desc), target).internalName
                } else if (insn is InvokeDynamicInsnNode) {
                    // desc
                    insn.desc = stubType(discovery, Type.getMethodType(insn.desc), target).descriptor
                    // bsm
                    insn.bsm = Handle(
                        insn.bsm.tag,
                        stubType(discovery, Type.getObjectType(insn.bsm.owner), target).internalName,
                        insn.bsm.name,
                        stubType(discovery, Type.getMethodType(insn.bsm.desc), target).descriptor,
                        insn.bsm.isInterface
                    )
                    // bsmArgs
                    for ((index, bsmArg) in insn.bsmArgs.withIndex()) {
                        if (bsmArg is Type) {
                            insn.bsmArgs[index] = stubType(discovery, bsmArg, target)
                        } else if (bsmArg is Handle) {
                            insn.bsmArgs[index] = Handle(
                                bsmArg.tag,
                                stubType(discovery, Type.getObjectType(bsmArg.owner), target).internalName,
                                bsmArg.name,
                                stubType(discovery, Type.getMethodType(bsmArg.desc), target).descriptor,
                                bsmArg.isInterface
                            )
                        }
                    }
                } else if (insn is LdcInsnNode) {
                    if (insn.cst is Type) {
                        val insnType = insn.cst as Type
                        insn.cst = stubType(discovery, insnType, target)
                    }
                } else if (insn is MultiANewArrayInsnNode) {
                    insn.desc = stubType(discovery, Type.getObjectType(insn.desc), target).descriptor
                } else if (insn is FrameNode) {
                    // TODO, maybe
                }
            }

        }

        return node
    }

    fun stubType(discovery: AnnotationDiscovery, type: Type, target: Version): Type {
        when (type.sort) {
            Type.METHOD -> {
                val args = type.argumentTypes
                var ret = type.returnType
                var i = 0
                while (i < args.size) {
                    args[i] = stubType(discovery, args[i], target)
                    i++
                }
                ret = stubType(discovery, ret, target)
                return Type.getMethodType(ret, *args)
            }

            Type.ARRAY -> {
                val dims = type.getDimensions()
                if (discovery.classStubs.containsKey(type.getElementType())) {
                    val desc = "[".repeat(dims) + discovery.classStubs[type.getElementType()][target]?.first?.descriptor
                    return Type.getType(desc)
                }
            }

            Type.OBJECT -> if (discovery.classStubs.containsKey(type)) {
                return Type.getType(discovery.classStubs[type][target]?.first?.descriptor)
            }

            else -> {}
        }
        return type
    }

    fun transformSignature(discovery: AnnotationDiscovery, sig: String, version: Version): String {
        val sw = SignatureWriter()
        SignatureReader(sig).accept(SignatureRemapper(sw, object : Remapper() {
            override fun mapType(internalName: String): String {
                if (discovery.classStubs.containsKey(Type.getObjectType(internalName))) {
                    return discovery.classStubs[Type.getObjectType(internalName)][version]?.first?.internalName ?: internalName
                }
                return internalName
            }
        }))
        return sw.toString()
    }

    fun stubMembers(discovery: AnnotationDiscovery, node: ClassNode, target: Version): ClassNode {
        for (method in node.methods) {
            if (method.instructions == null) continue
            var i = AtomicInteger(0)
            while (i.get() < method.instructions.size()) {
                stubInsn(discovery, method, i, target, node)
                i.incrementAndGet()
            }
        }
        return node
    }

    fun stubInsn(discovery: AnnotationDiscovery, mNode: MethodNode, insn: AtomicInteger, target: Version, classNode: ClassNode) {
        val insnNode = mNode.instructions[insn.get()]
        when (insnNode) {
            is MethodInsnNode -> {
                val member = FullyQualifiedMember(Type.getObjectType(insnNode.owner), insnNode.name, Type.getMethodType(insnNode.desc), insnNode.opcode == Opcodes.INVOKESTATIC)
                val owner = findMethodCallRealTarget(member, discovery.classpath) ?: member.owner
                val realMember = FullyQualifiedMember(owner, member.name, member.type, insnNode.opcode == Opcodes.INVOKESTATIC)
                val stub = stubMethod(discovery, realMember, target)
                if (stub != null) {
                    if (stub.type.sort != Type.METHOD) {
                        // determine if static and get/put based on signature
                        val returnType = realMember.type.returnType
                        val get = returnType != Type.VOID_TYPE
                        val opcode = if (stub.static) {
                            if (get) Opcodes.GETSTATIC else Opcodes.PUTSTATIC
                        } else {
                            if (get) Opcodes.GETFIELD else Opcodes.PUTFIELD
                        }
                        mNode.instructions.set(insnNode, FieldInsnNode(opcode, stub.owner.internalName, stub.name, stub.type.descriptor))
                    } else {
                        // determine if static based on desc
                        insnNode.opcode = if (stub.static) Opcodes.INVOKESTATIC else Opcodes.INVOKEVIRTUAL
                        insnNode.owner = stub.owner.internalName
                        insnNode.name = stub.name
                        insnNode.desc = stub.type.descriptor
                        if (realMember.name == "<init>") {
                            val j = insn.get() - 1
                            var skip = 0
                            while (j >= 0) {
                                val prev = mNode.instructions[j]
                                if (prev.opcode == Opcodes.NEW && prev is TypeInsnNode && prev.desc == realMember.owner.internalName && skip-- == 0) {
                                    mNode.instructions.remove(prev)
                                    // check and remove dup
                                    if (mNode.instructions[j].opcode == Opcodes.DUP) {
                                        mNode.instructions.remove(mNode.instructions[j])
                                    } else {
                                        throw IllegalStateException("Expected DUP after NEW")
                                    }
                                    break
                                } else if (prev.opcode == Opcodes.INVOKESPECIAL && prev is MethodInsnNode && prev.owner == realMember.owner.internalName && prev.name == "<init>") {
                                    skip++
                                }
                            }
                            if (j < 0) {
                                throw IllegalStateException("Could not find NEW for <init> call")
                            }
                            insn.set(insn.get() - 2)
                        }
                        if (realMember.name != "<init>" && Type.getReturnType(stub.type.descriptor) != realMember.type.returnType) {
                            val next = mNode.instructions[insn.get() + 1]
                            if (next.opcode != Opcodes.POP) {
                                // cast return
                                mNode.instructions.insertBefore(next, TypeInsnNode(Opcodes.CHECKCAST, realMember.type.returnType.internalName))
                                insn.incrementAndGet()
                            }
                        }
                    }
                } else if (discovery.modifys.contains(realMember)) {
                    val modify = discovery.modifys[realMember.copy(static = false)][target]?.first
                    if (modify != null) {
                        modify(null, *listOf(mNode, insn.get(), target, classNode).subList(0, modify.parameterCount).toTypedArray())
                    }
                }
            }
            is FieldInsnNode -> {
                val member = FullyQualifiedMember(Type.getObjectType(insnNode.owner), insnNode.name, Type.getType(insnNode.desc), insnNode.opcode == Opcodes.GETSTATIC || insnNode.opcode == Opcodes.PUTSTATIC)
                val owner = findFieldCallRealTarget(member, discovery.classpath) ?: member.owner
                val realMember = FullyQualifiedMember(owner, member.name, member.type, insnNode.opcode == Opcodes.GETSTATIC || insnNode.opcode == Opcodes.PUTSTATIC)
                val stub = stubField(discovery, realMember, target)
                if (stub != null) {
                    val replaceWith = when (insnNode.opcode) {
                        Opcodes.GETFIELD, Opcodes.GETSTATIC -> {
                            stub.getter
                        }
                        Opcodes.PUTFIELD, Opcodes.PUTSTATIC -> {
                            stub.setter
                        }
                        else -> throw IllegalStateException("Unknown field opcode ${insnNode.opcode}")
                    }
                    if (replaceWith != null) {
                        val getter = insnNode.opcode == Opcodes.GETFIELD || insnNode.opcode == Opcodes.GETSTATIC
                        val returnType = if (replaceWith.type.sort != Type.METHOD) {
                            insnNode.owner = replaceWith.owner.internalName
                            insnNode.name = replaceWith.name
                            insnNode.desc = replaceWith.type.descriptor
                            replaceWith.type
                        } else {
                            val params = replaceWith.type.argumentTypes
                            val returnType = replaceWith.type.returnType
                            mNode.instructions.set(insnNode,
                                MethodInsnNode(
                                    if (replaceWith.static) Opcodes.INVOKESTATIC else Opcodes.INVOKEVIRTUAL,
                                    replaceWith.owner.internalName,
                                    replaceWith.name,
                                    replaceWith.type.descriptor,
                                    false
                                )
                            )
                            returnType
                        }
                        if (getter && realMember.type != returnType) {
                            val next = mNode.instructions[insn.get() + 1]
                            if (next.opcode != Opcodes.POP) {
                                // cast return
                                mNode.instructions.insertBefore(next, TypeInsnNode(Opcodes.CHECKCAST, realMember.type.internalName))
                                insn.incrementAndGet()
                            }
                        }
                    }
                } else if (discovery.modifys.contains(realMember)) {
                    val modify = discovery.modifys[realMember][target]?.first
                    if (modify != null) {
                        modify(null, *listOf(mNode, insn.get(), target, classNode).subList(0, modify.parameterCount).toTypedArray())
                    }
                }
            }
            is InvokeDynamicInsnNode -> {
                val member = FullyQualifiedMember(Type.getObjectType(insnNode.bsm.owner), insnNode.bsm.name, Type.getMethodType(insnNode.bsm.desc), insnNode.bsm.tag == Opcodes.H_INVOKESTATIC)
                val owner = findMethodCallRealTarget(member, discovery.classpath) ?: member.owner
                val realMember = FullyQualifiedMember(owner, member.name, member.type, insnNode.bsm.tag == Opcodes.H_INVOKESTATIC)
                val stub = stubMethod(discovery, realMember, target)
                if (stub != null) {
                    insnNode.bsm = Handle(
                        if (stub.static) Opcodes.H_INVOKESTATIC else Opcodes.H_INVOKEVIRTUAL,
                        stub.owner.internalName,
                        stub.name,
                        stub.type.descriptor,
                        false
                    )
                }
                for (i in 0 until insnNode.bsmArgs.size) {
                    val arg = insnNode.bsmArgs[i]
                    if (arg is Handle) {
                        val member = FullyQualifiedMember(Type.getObjectType(arg.owner), arg.name, Type.getMethodType(arg.desc), arg.tag == Opcodes.H_INVOKESTATIC)
                        val owner = findMethodCallRealTarget(member, discovery.classpath) ?: member.owner
                        val realMember = FullyQualifiedMember(owner, member.name, member.type, arg.tag == Opcodes.H_INVOKESTATIC)
                        val stub = stubMethod(discovery, realMember, target)
                        if (stub != null) {
                            if (stub.type.sort != Type.METHOD) {
                                val returnType = realMember.type.returnType
                                // determine get/put based on signature
                                val get = returnType != Type.VOID_TYPE
                                val opcode = if (stub.static) {
                                    if (get) Opcodes.H_GETSTATIC else Opcodes.H_PUTSTATIC
                                } else {
                                    if (get) Opcodes.H_GETFIELD else Opcodes.H_PUTFIELD
                                }
                                insnNode.bsmArgs[i] = Handle(opcode, stub.owner.internalName, stub.name, stub.type.descriptor, false)
                            } else {
                                insnNode.bsmArgs[i] = Handle(
                                    if (stub.static) Opcodes.H_INVOKESTATIC else Opcodes.H_INVOKEVIRTUAL,
                                    stub.owner.internalName,
                                    stub.name,
                                    stub.type.descriptor,
                                    false
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun stubMethod(discovery: AnnotationDiscovery, member: FullyQualifiedMember, target: Version): FullyQualifiedMember? {
        if (member.type.sort != Type.METHOD) {
            throw IllegalArgumentException("member must be a method")
        }
        if (discovery.methodStubs.contains(member)) {
            val stubMember = discovery.methodStubs[member][target]?.first
            if (stubMember != null) {
                return stubMember
            }
        }
        if (discovery.replaces.contains(member)) {
            val replace = discovery.replaces[member][target]?.first
            if (replace != null) {
                return replace
            }
        }
        return null
    }

    fun stubField(discovery: AnnotationDiscovery, member: FullyQualifiedMember, target: Version): FieldMethods? {
        if (member.type.sort == Type.METHOD) {
            throw IllegalArgumentException("member must be a field")
        }
        if (discovery.fieldStubs.contains(member)) {
            val stubMember = discovery.fieldStubs[member][target]?.first
            if (stubMember != null) {
                return stubMember
            }
        }
        return null
    }

    fun remap(discovery: AnnotationDiscovery, nodes: Map<Type, ClassNode>): Map<Type, ClassNode> {
        val remapped = mutableMapOf<Type, ClassNode>()
        for ((type, node) in nodes) {
            remapped[type] = remap(discovery, node)
        }
        return remapped
    }

    fun removeSynthetics(discovery: AnnotationDiscovery, visitor: ClassVisitor): ClassVisitor = object : ClassVisitor(Opcodes.ASM9, visitor) {
        override fun visitMethod(
            access: Int,
            name: String?,
            descriptor: String?,
            signature: String?,
            exceptions: Array<out String>?
        ): MethodVisitor {
            return object : MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    descriptor: String,
                    isInterface: Boolean
                ) {
                    val type = Type.getObjectType(owner)
                    if (type in discovery.mergedData) {
                        val merged = discovery.mergedData[type]!!
                        val member = merged.third[MemberAndType(name, Type.getType(descriptor))]
                        if (member != null) {
                            // check if member is synthetic
                            if (member.synthetic) {
                                // check if starts with mv$castTo$
                                if (name.startsWith("mv\$castTo\$")) {
                                    // replace with checkcast
                                    super.visitTypeInsn(Opcodes.CHECKCAST, Type.getMethodType(descriptor).returnType.internalName)
                                    return
                                } else {
                                    throw IllegalStateException("Synthetic member $name in class $owner is not a known synthetic member")
                                }
                            }
                        }
                    }
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                }
            }
        }

    }

    fun remap(discovery: AnnotationDiscovery, node: ClassNode): ClassNode {
        val out = ClassNode()
        val remapper = ClassRemapper(out, object : Remapper() {
            override fun map(internalName: String): String {
                val type = Type.getObjectType(internalName)
                if (type in discovery.mergedData) {
                    return internalName.substringAfter("merged/")
                }
                return internalName
            }

            override fun mapFieldName(owner: String, name: String, descriptor: String): String {
                return mapMemberName(owner, name, descriptor)
            }

            override fun mapMethodName(owner: String, name: String, descriptor: String): String {
                return mapMemberName(owner, name, descriptor)
            }

            fun mapMemberName(owner: String, name: String, descriptor: String): String {
                val type = Type.getObjectType(owner)
                if (type in discovery.mergedData) {
                    val merged = discovery.mergedData[type]!!
                    val member = merged.third[MemberAndType(name, Type.getType(descriptor))]
                    if (member != null) {
                        // check if member is renamed
                        if (member.name != "") {
                            return member.name
                        }
                        return name
                    }
                }
                return name

            }
        })
        node.accept(removeSynthetics(discovery, remapper))
        return out
    }

    private fun write(transformed: Map<Type, ClassNode>, mergeData: Map<Type, Triple<Type, MergedClass, MutableMap<MemberAndType, MergedMember>>>, output: Path, target: Version) {
        output.deleteIfExists()
        output.openZipFileSystem(mapOf("create" to true)).use { fs ->
            for ((type, node) in transformed) {
                fs.getPath(type.internalName + ".class").apply {
                    parent?.createDirectories()
                    writeBytes(write(transformed, mergeData, node, target))
                }
            }
        }
    }

    private fun write(transformed: Map<Type, ClassNode>, mergeData: Map<Type, Triple<Type, MergedClass, MutableMap<MemberAndType, MergedMember>>>, node: ClassNode, target: Version): ByteArray {
        val writer = ASMClassWriter(ClassWriter.COMPUTE_MAXS and ClassWriter.COMPUTE_FRAMES) {
            val type = Type.getObjectType(it)
            transformed[type]?.superName ?: mergeData[type]?.second?.inheritance?.firstOrNull { it.versions.contains(target) }?.superClass ?:  mergeData[type]?.first?.internalName
        }
        node.accept(writer)
        return writer.toByteArray()
    }

}