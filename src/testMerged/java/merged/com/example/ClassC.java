package merged.com.example;


import xyz.wagyourtail.multiversion.injected.merge.annotations.MergedClass;
import xyz.wagyourtail.multiversion.injected.merge.annotations.MergedMember;

@MergedClass(versions = { "a", "b" })
public class ClassC extends ClassB {

    @MergedMember(versions = { "a" })
    private String fieldA;

    @MergedMember(versions = { "a" })
    public ClassC(String arg0) {
        throw new AssertionError();
    }

    @MergedMember(versions = { "b" })
    protected ClassC(String arg0, String arg1) {
        throw new AssertionError();
    }

}
