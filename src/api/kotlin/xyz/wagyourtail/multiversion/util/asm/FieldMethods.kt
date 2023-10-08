package xyz.wagyourtail.multiversion.util.asm

data class FieldMethods(val setter: FullyQualifiedMember?, val getter: FullyQualifiedMember?) {

    constructor() : this(null, null)

    fun withSetter(setter: FullyQualifiedMember?): FieldMethods {
        return FieldMethods(setter, getter)
    }

    fun withGetter(getter: FullyQualifiedMember?): FieldMethods {
        return FieldMethods(setter, getter)
    }

    override fun toString(): String {
        return super.toString()
    }

}
