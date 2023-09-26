package com.example.run;

import com.example.ClassA;
import xyz.wagyourtail.multiversion.injected.split.annotations.Stub;

public class ClassATest {

    @Stub(versions = {"a"})
    public static String methodB(ClassA a) {
        return "methodB";
    }

    public static void main(String[] args) {
        ClassA a = new ClassA();
        System.out.println(a.fieldA);
        a.methodA();
        a.methodC();
        System.out.println(methodB(a));
    }

}
