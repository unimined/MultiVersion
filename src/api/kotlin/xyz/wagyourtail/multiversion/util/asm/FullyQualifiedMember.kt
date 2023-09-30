package xyz.wagyourtail.multiversion.util.asm

import org.objectweb.asm.Type

data class FullyQualifiedMember(val owner: Type, val name: String, val type: Type, val static: Boolean) {

    val memberAndType by lazy { MemberAndType(name, type) }

}