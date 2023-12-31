package xyz.wagyourtail.multiversion.util.asm

import org.objectweb.asm.Type

data class MemberAndType(val name: String, val type: Type) {

    override fun toString(): String {
        return "$name ${type.descriptor}"
    }

}