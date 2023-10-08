package xyz.wagyourtail.multiversion.split

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import xyz.wagyourtail.multiversion.injected.merge.annotations.MergedClass
import xyz.wagyourtail.multiversion.injected.merge.annotations.MergedMember
import xyz.wagyourtail.multiversion.injected.split.annotations.*
import xyz.wagyourtail.multiversion.util.*
import xyz.wagyourtail.multiversion.util.asm.FieldMethods
import xyz.wagyourtail.multiversion.util.asm.FullyQualifiedMember
import xyz.wagyourtail.multiversion.util.asm.MemberAndType
import java.lang.reflect.Method
import java.nio.file.Path

class AnnotationDiscovery(val input: Path, val mergedClasspath: Set<Path>) {
    val inputClasses = mutableMapOf<Type, ClassNode>()
    val classpath = mutableMapOf<Type, ClassNode>()

    val classStubs = defaultedMapOf<Type, MutableMap<Version, Pair<Type, Stub>>> { mutableMapOf() }
    val revClassStubs = defaultedMapOf<Type, MutableSet<Version>> { mutableSetOf() }
    val methodStubs = defaultedMapOf<FullyQualifiedMember, MutableMap<Version, Pair<FullyQualifiedMember, Stub>>> { mutableMapOf() }
    val revMethodStubs = defaultedMapOf<FullyQualifiedMember, MutableSet<Version>> { mutableSetOf() }
    val fieldStubs = defaultedMapOf<FullyQualifiedMember, MutableMap<Version, Pair<FieldMethods, Stub>>> { mutableMapOf() }
    val revFieldStubs = defaultedMapOf<FullyQualifiedMember, MutableSet<Version>> { mutableSetOf() }

    val replaces = defaultedMapOf<FullyQualifiedMember, MutableMap<Version, Pair<FullyQualifiedMember, Replace>>> { mutableMapOf() }

    val modifys = defaultedMapOf<FullyQualifiedMember, MutableMap<Version, Pair<Method, Modify>>> { mutableMapOf() }
    val revModifys = mutableSetOf<FullyQualifiedMember>()

    val revClassRemoves = defaultedMapOf<Type, MutableSet<Version>> { mutableSetOf() }
    val revMethodRemoves = defaultedMapOf<FullyQualifiedMember, MutableSet<Version>> { mutableSetOf() }
    val revFieldRemoves = defaultedMapOf<FullyQualifiedMember, MutableSet<Version>> { mutableSetOf() }

    val revClassVersioned = defaultedMapOf<Type, MutableSet<Version>> { mutableSetOf() }
    val revMethodVersioned = defaultedMapOf<FullyQualifiedMember, MutableSet<Version>> { mutableSetOf() }
    val revFieldVersioned = defaultedMapOf<FullyQualifiedMember, MutableSet<Version>> { mutableSetOf() }

    val mergedData = mutableMapOf<Type, Triple<Type, MergedClass, MutableMap<MemberAndType, MergedMember>>>()


    private val initData by lazy {
        findStubs()
        findMerged()
        ""
    }

    fun init() {
        print(initData)
    }

    private fun findStubs() {
        // stage 1: read all classes for stub/modify/replace
        input.forEachInZip { s, inputStream ->
            if (!s.endsWith(".class")) return@forEachInZip
            val node = inputStream.readClass()
            inputClasses[Type.getObjectType(node.name)] = node
            classpath[Type.getObjectType(node.name)] = node
            // check if class is annotated
            val classStubAnnotations = node.invisibleAnnotations?.firstOrNull { Type.getType(it.desc) == Type.getType(Stub::class.java) }?.let { listOf(
                NodeLoader.fromNode<Stub>(it)) } ?:
            node.invisibleAnnotations?.firstOrNull { Type.getType(it.desc) == Type.getType(Stub.StubHolder::class.java) }?.let { NodeLoader.fromNode<Stub.StubHolder>(it) }?.value?.toList()
            if (classStubAnnotations != null) {
                if (node.access and Opcodes.ACC_PUBLIC == 0) {
                    throw IllegalArgumentException("stub ${node.name} must be public")
                }
                for (stub in classStubAnnotations) {
                    classStubs[Type.getObjectType(stub.ref.value.toInternalName())].putAll(stub.versions.associateWith { (Type.getObjectType(node.name) to stub) })
                    revClassStubs[Type.getObjectType(node.name)] += stub.versions.toSet()
                }
            }
            val classRemoveAnnotation = node.invisibleAnnotations?.firstOrNull { Type.getType(it.desc) == Type.getType(Remove::class.java) }?.let { NodeLoader.fromNode<Remove>(it) }
            if (classRemoveAnnotation != null) {
                revClassRemoves[Type.getObjectType(node.name)] += classRemoveAnnotation.versions.toSet()
            }
            val classVersionedAnnotation = node.invisibleAnnotations?.firstOrNull { Type.getType(it.desc) == Type.getType(Versioned::class.java) }?.let { NodeLoader.fromNode<Versioned>(it) }
            if (classVersionedAnnotation != null) {
                revClassVersioned[Type.getObjectType(node.name)] += classVersionedAnnotation.versions.toSet()
            }
            if (classRemoveAnnotation != null && classVersionedAnnotation != null) {
                throw IllegalArgumentException("class ${node.name} cannot be both versioned and removed")
            }
            for (mNode in node.methods) {
                val methodStubAnnotations = mNode.invisibleAnnotations?.firstOrNull { Type.getType(it.desc) == Type.getType(Stub::class.java) }?.let { listOf(
                    NodeLoader.fromNode<Stub>(it)) } ?:
                mNode.invisibleAnnotations?.firstOrNull { Type.getType(it.desc) == Type.getType(Stub.StubHolder::class.java) }?.let { NodeLoader.fromNode<Stub.StubHolder>(it) }?.value?.toList()
                if (methodStubAnnotations != null) {
                    if (mNode.access and Opcodes.ACC_STATIC == 0 || mNode.access and Opcodes.ACC_PUBLIC == 0) throw IllegalArgumentException("stub ${node.name}.${mNode.name} must be public static")
                    val methodDesc = Type.getMethodType(mNode.desc)
                    val params = methodDesc.argumentTypes
                    val returns = methodDesc.returnType
                    for (stub in methodStubAnnotations) {
                        val (targetParams, targetOwner) = if (stub.ref.value != "") params to Type.getObjectType(stub.ref.value.toInternalName()) else {
                            params.slice(1 until params.size).toTypedArray() to params[0]
                        }
                        val field = stub.field || (stub.ref.desc != "" && !stub.ref.desc.startsWith("("))
                        if (field) {
                            // equivalent to !((returns != Type.VOID_TYPE && targetParams.isEmpty()) || (returns == Type.VOID_TYPE && targetParams.size == 1))
                            // which checks if it is neither a setter or getter
                            if ((returns == Type.VOID_TYPE || targetParams.isNotEmpty()) && (returns != Type.VOID_TYPE || targetParams.size != 1)) {
                                throw IllegalArgumentException("Unexpected parameters/return, expected field setter or getter replacer method for ${node.name}.${mNode.name}")
                            }
                            val targetName = if (stub.ref.member != "") stub.ref.member else mNode.name
                            val targetDesc = if (stub.ref.desc != "") Type.getType(stub.ref.desc) else if (params.size == 1) { targetParams[0] } else { returns }
                            if (targetDesc.sort == Type.METHOD) {
                                throw IllegalArgumentException("Unexpected type for ref target, expected field type for ${node.name}.${mNode.name}")
                            }
                            val getter = returns != Type.VOID_TYPE
                            for (version in stub.versions) {
                                val target = FullyQualifiedMember(targetOwner, targetName, targetDesc, true)
                                val existingGetter = fieldStubs[target][version]?.first?.getter
                                val existingSetter = fieldStubs[target][version]?.first?.setter
                                if (getter) {
                                    fieldStubs[target][version] = Pair(FieldMethods(existingSetter, FullyQualifiedMember(Type.getObjectType(node.name), mNode.name, Type.getMethodType(mNode.desc), true)), stub)
                                    revFieldStubs[FullyQualifiedMember(Type.getObjectType(node.name), mNode.name, Type.getMethodType(mNode.desc), true)] += stub.versions.toSet()
                                } else {
                                    fieldStubs[target][version] = Pair(FieldMethods(FullyQualifiedMember(Type.getObjectType(node.name), mNode.name, Type.getMethodType(mNode.desc), true), existingGetter), stub)
                                    revFieldStubs[FullyQualifiedMember(Type.getObjectType(node.name), mNode.name, Type.getMethodType(mNode.desc), true)] += stub.versions.toSet()
                                }
                            }
                        } else {
                            val targetName = if (stub.ref.member != "") stub.ref.member else mNode.name
                            val targetDesc = if (stub.ref.desc != "") Type.getMethodType(stub.ref.desc) else Type.getMethodType(returns, *targetParams)
                            methodStubs[FullyQualifiedMember(targetOwner, targetName, targetDesc, targetParams.size == params.size)].putAll(stub.versions.associateWith { FullyQualifiedMember(Type.getObjectType(node.name), mNode.name, Type.getMethodType(mNode.desc), true) to stub })
                            revMethodStubs[FullyQualifiedMember(Type.getObjectType(node.name), mNode.name, Type.getMethodType(mNode.desc), true)] += stub.versions.toSet()
                        }
                    }
                }

                val replaceAnnotations = mNode.invisibleAnnotations?.firstOrNull { Type.getType(it.desc) == Type.getType(Replace::class.java) }?.let { listOf(
                    NodeLoader.fromNode<Replace>(it)) } ?:
                mNode.invisibleAnnotations?.firstOrNull { Type.getType(it.desc) == Type.getType(Replace.ReplaceHolder::class.java) }?.let { NodeLoader.fromNode<Replace.ReplaceHolder>(it) }?.value?.toList()
                if (replaceAnnotations != null) {
                    if (mNode.access and Opcodes.ACC_STATIC == 0 || mNode.access and Opcodes.ACC_PUBLIC == 0) throw IllegalArgumentException("stub ${node.name}.${mNode.name} must be public static")
                    val methodDesc = Type.getMethodType(mNode.desc)
                    val params = methodDesc.argumentTypes
                    val returns = methodDesc.returnType

                    for (replace in replaceAnnotations) {
                        val targetOwner = if (replace.ref.value != "") Type.getObjectType(replace.ref.value.toInternalName()) else params[0]
                        val field = replace.field || (replace.ref.desc != "" && !replace.ref.desc.startsWith("("))
                        if (field) {
                            // check if non-static getter/setter desc
                            val static = !((returns != Type.VOID_TYPE && params.size == 1) || (returns == Type.VOID_TYPE && params.size == 2))
                            val fParams = if (static) {
                                params
                            } else {
                                params.slice(1 until params.size).toTypedArray()
                            }
                            if (!((returns != Type.VOID_TYPE && fParams.isEmpty()) || (returns == Type.VOID_TYPE && fParams.size == 1))) {
                                throw IllegalArgumentException("Unexpected parameters/return, expected field setter or getter replacer method for ${node.name}.${mNode.name}")
                            }
                            val targetName = if (replace.ref.member != "") replace.ref.member else mNode.name
                            val targetDesc = if (replace.ref.desc != "") Type.getType(replace.ref.desc) else if (fParams.size == 1) { fParams[0] } else { returns }
                            if (targetDesc.sort == Type.METHOD) {
                                throw IllegalArgumentException("Unexpected type for ref target, expected field type for ${node.name}.${mNode.name}")
                            }
                            val getter = returns != Type.VOID_TYPE
                            for (version in replace.versions) {
                                val target = FullyQualifiedMember(targetOwner, targetName, targetDesc, static)
                                replaces[FullyQualifiedMember(Type.getObjectType(node.name), mNode.name, Type.getMethodType(mNode.desc), true)].putAll(replace.versions.associateWith { target to replace })
                            }
                        } else {
                            val targetName = if (replace.ref.member != "") replace.ref.member else mNode.name
                            val targetDesc = if (replace.ref.desc != "") Type.getMethodType(replace.ref.desc) else if (replace.ref.value != "") methodDesc else Type.getMethodType(returns, *params.slice(1 until params.size).toTypedArray())
                            val target = FullyQualifiedMember(targetOwner, targetName, targetDesc, targetDesc.argumentTypes.size == methodDesc.argumentTypes.size)
                            replaces[FullyQualifiedMember(Type.getObjectType(node.name), mNode.name, Type.getMethodType(mNode.desc), true)].putAll(replace.versions.associateWith { target to replace })
                        }
                    }
                }

                val modifyAnnotations = mNode.invisibleAnnotations?.firstOrNull { Type.getType(it.desc) == Type.getType(Modify::class.java) }?.let { listOf(
                    NodeLoader.fromNode<Modify>(it)) } ?:
                mNode.invisibleAnnotations?.firstOrNull { Type.getType(it.desc) == Type.getType(Modify.ModifyHolder::class.java) }?.let { NodeLoader.fromNode<Modify.ModifyHolder>(it) }?.value?.toList()
                if (modifyAnnotations != null) {
                    if (mNode.access and Opcodes.ACC_STATIC == 0 || mNode.access and Opcodes.ACC_PUBLIC == 0) throw IllegalArgumentException("modify ${node.name}.${mNode.name} must be public static")
                    val methodDesc = Type.getMethodType(mNode.desc)
                    val params = methodDesc.argumentTypes
                    for (modify in modifyAnnotations) {
                        val targetOwner = if (modify.ref.value != "") Type.getObjectType(modify.ref.value.toInternalName()) else throw IllegalArgumentException("modify ${node.name}.${mNode.name} must have full ref")
                        val targetName = if (modify.ref.member != "") modify.ref.member else throw IllegalArgumentException("modify ${node.name}.${mNode.name} must have full ref")
                        val targetDesc = if (modify.ref.desc != "") Type.getMethodType(modify.ref.desc) else throw IllegalArgumentException("modify ${node.name}.${mNode.name} must have full ref")
                        val method = NodeLoader.fromNode(node.name, mNode)
                        // ensure modify params startWith method parameters
                        val mParams = method.parameterTypes
                        for (i in mParams.indices) {
                            if (Modify.PARAMS[i] != mParams[i]) {
                                throw IllegalArgumentException("modify ${node.name}.${mNode.name} must have matching parameters. expected ${Modify.PARAMS[i]} actual ${mParams[i]}")
                            }
                        }
                        modifys[FullyQualifiedMember(targetOwner, targetName, targetDesc, false)].putAll(modify.versions.associateWith { method to modify })
                        revModifys += FullyQualifiedMember(Type.getObjectType(node.name), mNode.name, Type.getMethodType(mNode.desc), true)
                    }
                }
                val methodRemoveAnnotation = mNode.invisibleAnnotations?.firstOrNull { Type.getType(it.desc) == Type.getType(Remove::class.java) }?.let { NodeLoader.fromNode<Remove>(it) }
                if (methodRemoveAnnotation != null) {
                    revMethodRemoves[FullyQualifiedMember(Type.getObjectType(node.name), mNode.name, Type.getMethodType(mNode.desc), true)] += methodRemoveAnnotation.versions.toSet()
                }
                val methodVersionedAnnotation = mNode.invisibleAnnotations?.firstOrNull { Type.getType(it.desc) == Type.getType(Versioned::class.java) }?.let { NodeLoader.fromNode<Versioned>(it) }
                if (methodVersionedAnnotation != null) {
                    revMethodVersioned[FullyQualifiedMember(Type.getObjectType(node.name), mNode.name, Type.getMethodType(mNode.desc), true)] += methodVersionedAnnotation.versions.toSet()
                }
                if (methodRemoveAnnotation != null && methodVersionedAnnotation != null) {
                    throw IllegalArgumentException("method ${node.name}.${mNode.name} cannot be both versioned and removed")
                }
            }

            for (fNode in node.fields) {
                val fieldStubAnnotations = fNode.invisibleAnnotations?.firstOrNull { Type.getType(it.desc) == Type.getType(Stub::class.java) }?.let { listOf(
                    NodeLoader.fromNode<Stub>(it)) } ?:
                fNode.invisibleAnnotations?.firstOrNull { Type.getType(it.desc) == Type.getType(Stub.StubHolder::class.java) }?.let { NodeLoader.fromNode<Stub.StubHolder>(it) }?.value?.toList()
                if (fieldStubAnnotations != null) {
                    if (fNode.access and Opcodes.ACC_STATIC == 0 || fNode.access and Opcodes.ACC_PUBLIC == 0) throw IllegalArgumentException("stub ${node.name}.${fNode.name} must be public static")
                    for (stub in fieldStubAnnotations) {
                        val targetOwner = if (stub.ref.value != "") Type.getObjectType(stub.ref.value.toInternalName()) else throw IllegalArgumentException("field stub ${node.name}.${fNode.name} must have ref")
                        val targetName = if (stub.ref.member != "") stub.ref.member else fNode.name
                        val targetDesc = if (stub.ref.desc != "") Type.getType(stub.ref.desc) else Type.getType(fNode.desc)
                        val field = FullyQualifiedMember(Type.getObjectType(node.name), fNode.name, Type.getType(fNode.desc), true)
                        fieldStubs[FullyQualifiedMember(targetOwner, targetName, targetDesc, true)].putAll(stub.versions.associateWith { FieldMethods(field, field) to stub })
                        revFieldStubs[field] += stub.versions.toSet()
                    }
                }
                val fieldRemoveAnnotation = fNode.invisibleAnnotations?.firstOrNull { Type.getType(it.desc) == Type.getType(Remove::class.java) }?.let { NodeLoader.fromNode<Remove>(it) }
                if (fieldRemoveAnnotation != null) {
                    revFieldRemoves[FullyQualifiedMember(Type.getObjectType(node.name), fNode.name, Type.getType(fNode.desc), true)] += fieldRemoveAnnotation.versions.toSet()
                }
                val fieldVersionedAnnotation = fNode.invisibleAnnotations?.firstOrNull { Type.getType(it.desc) == Type.getType(Versioned::class.java) }?.let { NodeLoader.fromNode<Versioned>(it) }
                if (fieldVersionedAnnotation != null) {
                    revFieldVersioned[FullyQualifiedMember(Type.getObjectType(node.name), fNode.name, Type.getType(fNode.desc), true)] += fieldVersionedAnnotation.versions.toSet()
                }
                if (fieldRemoveAnnotation != null && fieldVersionedAnnotation != null) {
                    throw IllegalArgumentException("field ${node.name}.${fNode.name} cannot be both versioned and removed")
                }
            }
        }
    }

    private fun findMerged() {
        for (lib in mergedClasspath) {
            lib.forEachInZip { s, inputStream ->
                if (!s.startsWith("merged/") || !s.endsWith(".class")) return@forEachInZip
                val node = inputStream.readClass()
                classpath[Type.getObjectType(node.name)] = node
                val mergedClass = node.invisibleAnnotations?.firstOrNull { Type.getType(it.desc) == Type.getType(MergedClass::class.java) }
                if (mergedClass == null) return@forEachInZip
                val mergedMembers = mutableMapOf<MemberAndType, MergedMember>()
                for (mNode in node.methods) {
                    val mergedMember = mNode.invisibleAnnotations?.firstOrNull { Type.getType(it.desc) == Type.getType(MergedMember::class.java) }
                    if (mergedMember != null) {
                        mergedMembers[MemberAndType(mNode.name, Type.getMethodType(mNode.desc))] = NodeLoader.fromNode(mergedMember)
                    }
                }
                for (fNode in node.fields) {
                    val mergedMember = fNode.invisibleAnnotations?.firstOrNull { Type.getType(it.desc) == Type.getType(MergedMember::class.java) }
                    if (mergedMember != null) {
                        mergedMembers[MemberAndType(fNode.name, Type.getType(fNode.desc))] = NodeLoader.fromNode(mergedMember)
                    }
                }
                mergedData[Type.getObjectType(node.name)] = Triple(Type.getObjectType(node.superName), NodeLoader.fromNode<MergedClass>(mergedClass), mergedMembers)
            }
        }
    }
}