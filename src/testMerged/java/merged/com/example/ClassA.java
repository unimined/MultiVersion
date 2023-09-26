package merged.com.example;

import xyz.wagyourtail.multiversion.injected.merge.annotations.Access;
import xyz.wagyourtail.multiversion.injected.merge.annotations.MergedClass;
import xyz.wagyourtail.multiversion.injected.merge.annotations.MergedMember;

@MergedClass(versions = { "a", "b" })
public class ClassA {

    @MergedMember(versions = { "a" })
    public String fieldA;

    @MergedMember(versions = { "a", "b" })
    public ClassA() {
        throw new AssertionError();
    }

    @MergedMember(versions = { "a", "b" })
    public void methodA() {
        throw new AssertionError();
    }
    @MergedMember(versions = { "a", "b" }, access = { @Access(value = "public", versions = "a"), @Access(value = "protected", versions = "b") })
    public void methodC() {
        throw new AssertionError();
    }

    @MergedMember(versions = { "b" })
    public String methodB() {
        throw new AssertionError();
    }


}