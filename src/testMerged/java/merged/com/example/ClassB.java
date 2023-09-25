package merged.com.example;

import xyz.wagyourtail.multiversion.injected.merge.annotations.Inheritance;
import xyz.wagyourtail.multiversion.injected.merge.annotations.MergedClass;
import xyz.wagyourtail.multiversion.injected.merge.annotations.MergedMember;

@MergedClass(versions = { "a", "b" }, inheritance = { @Inheritance(versions = { "b" }, superClass = ClassA.class) })
public class ClassB {

    @MergedMember(versions = { "b" }, synthetic = true)
    public ClassA mv$castTo$com_example_ClassA() {
        throw new AssertionError();
    }

    @MergedMember(versions = { "a", "b" })
    public ClassB(String arg0) {
        throw new AssertionError();
    }

    @MergedMember(name = "conflict", versions = { "a" })
    public long conflict$mv$a() {
        throw new AssertionError();
    }

    @MergedMember(versions = { "b" })
    public ClassB() {
        throw new AssertionError();
    }

    @MergedMember(name = "conflict", versions = { "b" })
    public int conflict$mv$b() {
        throw new AssertionError();
    }

}
