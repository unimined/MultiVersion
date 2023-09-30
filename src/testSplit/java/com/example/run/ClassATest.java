package com.example.run;

import merged.com.example.ClassA;
import xyz.wagyourtail.multiversion.injected.split.annotations.Ref;
import xyz.wagyourtail.multiversion.injected.split.annotations.Remove;
import xyz.wagyourtail.multiversion.injected.split.annotations.Replace;
import xyz.wagyourtail.multiversion.injected.split.annotations.Stub;

public class ClassATest extends @Remove(versions = "a") ClassA {

    @Replace(versions = {"a"}, ref = @Ref(member = "fieldA"), field = true)
    @Replace(versions = {"b"}, ref = @Ref(member = "methodB"))
    public static String fieldToMethod(ClassA a) {
        throw new AssertionError();
    }

    @Stub(versions = {"a"})
    public static String methodB(ClassA a) {
        return "methodB";
    }

    @Stub(versions = {"b"})
    public static void methodC(ClassA a) {
        System.out.println("methodC");
    }

    public static void main(String[] args) {
        ClassA a = new ClassA();
        System.out.println(fieldToMethod(a));
        a.methodA();
        a.methodC();
        System.out.println(a.methodB());
    }

}
