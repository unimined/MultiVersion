package xyz.wagyourtail.multiversion.split

import org.objectweb.asm.*
import org.objectweb.asm.tree.ClassNode
import xyz.wagyourtail.multiversion.util.asm.FullyQualifiedMember
import xyz.wagyourtail.multiversion.util.asm.findFieldCallRealTarget
import xyz.wagyourtail.multiversion.util.asm.findMethodCallRealTarget

object SplitVerifier {

    fun verify(discovery: AnnotationDiscovery, transformed: Map<Type, ClassNode>, target: Version) {
        for (node in transformed.values) {
            verify(discovery, node, target)
        }
    }

    fun verify(discovery: AnnotationDiscovery, node: ClassNode, target: Version) {
        node.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(
                version: Int,
                access: Int,
                name: String,
                signature: String?,
                superName: String,
                interfaces: Array<out String>?
            ) {
                if (verifyClass(discovery, Type.getObjectType(superName), target)) {
                    throw VerifyVersionException("Class $name extends $superName which is not merged for version $target")
                }
                for (interfaceName in interfaces ?: emptyArray()) {
                    if (verifyClass(discovery, Type.getObjectType(interfaceName), target)) {
                        throw VerifyVersionException("Class $name implements $interfaceName which is not merged for version $target")
                    }
                }
            }

            override fun visitMethod(
                access: Int,
                mName: String,
                mDesc: String,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor {
                // descriptor
                val methodType = Type.getMethodType(mDesc)
                val parameterTypes = methodType.argumentTypes
                val returnType = methodType.returnType
                if (verifyClass(discovery, returnType, target)) {
                    throw VerifyVersionException("Method $mName in class ${node.name} returns $returnType which is not merged for version $target")
                }
                for (parameterType in parameterTypes) {
                    if (verifyClass(discovery, parameterType, target)) {
                        throw VerifyVersionException("Method $mName in class ${node.name} has parameter $parameterType which is not merged for version $target")
                    }
                }
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                        val static = opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC
                        val realOwner = findFieldCallRealTarget(FullyQualifiedMember(Type.getObjectType(owner), name, Type.getType(descriptor), opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC), discovery.classpath) ?: Type.getObjectType(owner)
                        if (verifyClass(discovery, Type.getObjectType(owner), target)) {
                            throw VerifyVersionException("Method $mName in class ${node.name} contains call to $owner which is not merged for version $target")
                        }
                        if (verifyMethodOrFieldCall(discovery, FullyQualifiedMember(realOwner, name, Type.getType(descriptor), static), target)) {
                            throw VerifyVersionException("Method $mName in class $${node.name} contains call to $realOwner.$name which is not merged for version $target")
                        }
                    }

                    override fun visitMethodInsn(
                        opcode: Int,
                        owner: String,
                        name: String,
                        descriptor: String,
                        isInterface: Boolean
                    ) {
                        val static = opcode == Opcodes.INVOKESTATIC
                        val realOwner = findFieldCallRealTarget(FullyQualifiedMember(Type.getObjectType(owner), name, Type.getType(descriptor), opcode == Opcodes.INVOKESTATIC), discovery.classpath) ?: Type.getObjectType(owner)
                        if (verifyClass(discovery, Type.getObjectType(owner), target)) {
                            throw VerifyVersionException("Method $mName in class ${node.name} contains call to $owner which is not merged for version $target")
                        }
                        if (verifyMethodOrFieldCall(discovery, FullyQualifiedMember(realOwner, name, Type.getMethodType(descriptor), static), target)) {
                            throw VerifyVersionException("Method $mName in class ${node.name} contains call to $realOwner.$name which is not merged for version $target")
                        }
                    }

                    override fun visitInvokeDynamicInsn(
                        name: String,
                        descriptor: String,
                        bootstrapMethodHandle: Handle,
                        vararg bootstrapMethodArguments: Any
                    ) {
                        // check handles and types
                        val bsmStatic = bootstrapMethodHandle.tag == Opcodes.H_INVOKESTATIC
                        val realOwner = findMethodCallRealTarget(FullyQualifiedMember(Type.getObjectType(bootstrapMethodHandle.owner), bootstrapMethodHandle.name, Type.getType(bootstrapMethodHandle.desc), bootstrapMethodHandle.tag == Opcodes.H_INVOKESTATIC), discovery.classpath) ?: Type.getObjectType(bootstrapMethodHandle.owner)
                        if (verifyMethodOrFieldCall(discovery, FullyQualifiedMember(realOwner, bootstrapMethodHandle.name, Type.getType(bootstrapMethodHandle.desc), bsmStatic), target)) {
                            throw VerifyVersionException("Method $mName in class ${node.name} contains call to $bootstrapMethodHandle which is not merged for version $target")
                        }
                        for (arg in bootstrapMethodArguments) {
                            if (arg is Handle) {
                                val realOwner = findMethodCallRealTarget(FullyQualifiedMember(Type.getObjectType(arg.owner), arg.name, Type.getType(arg.desc), arg.tag == Opcodes.H_INVOKESTATIC || arg.tag == Opcodes.H_PUTSTATIC || arg.tag == Opcodes.H_GETSTATIC), discovery.classpath) ?: Type.getObjectType(arg.owner)
                                val hStatic = arg.tag == Opcodes.H_INVOKESTATIC || arg.tag == Opcodes.H_PUTSTATIC || arg.tag == Opcodes.H_GETSTATIC
                                if (verifyMethodOrFieldCall(discovery, FullyQualifiedMember(realOwner, arg.name, Type.getType(arg.desc), hStatic), target)) {
                                    throw VerifyVersionException("Method $mName in class ${node.name} contains call/reference to $arg which is not merged for version $target")
                                }
                            } else if (arg is Type) {
                                if (verifyClass(discovery, arg, target)) {
                                    throw VerifyVersionException("Method $mName in class ${node.name} contains call/reference to $arg which is not merged for version $target")
                                }
                            }
                        }
                    }

                    override fun visitLdcInsn(value: Any) {
                        if (value is Type) {
                            if (verifyClass(discovery, value, target)) {
                                throw VerifyVersionException("Method $mName in class ${node.name} contains reference to $value which is not merged for version $target")
                            }
                        }
                    }

                    override fun visitTypeInsn(opcode: Int, type: String) {
                        if (verifyClass(discovery, Type.getObjectType(type), target)) {
                            throw VerifyVersionException("Method $mName in class ${node.name} contains reference to $type which is not merged for version $target")
                        }
                    }

                    override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) {
                        if (verifyClass(discovery, Type.getType(descriptor), target)) {
                            throw VerifyVersionException("Method $mName in class ${node.name} contains reference to $descriptor which is not merged for version $target")
                        }
                    }
                }
            }
        })
    }

    fun verifyClass(discovery: AnnotationDiscovery, type: Type, target: Version): Boolean {
        if (type.sort == Type.ARRAY) return verifyClass(discovery, type.elementType, target)
        // mergedData doesn't contain version
        if (discovery.mergedData.containsKey(type) && !discovery.mergedData[type]!!.second.versions.contains(target)) {
            return true

        }
        // stubs don't contain version
        if (discovery.revClassStubs.containsKey(type) && !discovery.revClassStubs[type].contains(target)) {
            return true
        }
        return false
    }

    fun verifyMethodOrFieldCall(discovery: AnnotationDiscovery, member: FullyQualifiedMember, target: Version): Boolean {
        // mergedData doesn't contain version
        if (discovery.mergedData.containsKey(member.type)) {
            if (discovery.mergedData[member.type]!!.third.containsKey(member.memberAndType) && !discovery.mergedData[member.type]!!.third[member.memberAndType]!!.versions.contains(target)) {
                return true
            }
        }
        if (member.type.sort == Type.METHOD) {
            // stubs don't contain version
            if (discovery.revMethodStubs.containsKey(member)) {
                if (!discovery.revMethodStubs[member].contains(target)) {
                    return true
                }
            }
            // replaces contains version
            if (discovery.replaces.containsKey(member) && discovery.replaces[member].containsKey(target)) {
                return true
            }
            // modify
            if (discovery.modifys.containsKey(member)) {
                return true
            }
            // TODO: remove
            // TODO: versioned
        } else {
            // stubs don't contain version
            if (discovery.revFieldStubs.containsKey(member)) {
                if (!discovery.revFieldStubs[member].contains(target)) {
                    return true
                }
            }
        }
        return false
    }

    class VerifyVersionException(message: String) : Exception(message)

}