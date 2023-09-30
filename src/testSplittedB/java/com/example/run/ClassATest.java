package com.example.run;

import com.example.ClassA;
import xyz.wagyourtail.multiversion.injected.split.annotations.Stub;

public class ClassATest extends ClassA {

    @Stub(versions = {"b"})
    public static void methodC(ClassA a) {
        System.out.println("methodC");
    }

    public static void main(String[] args) {
        ClassA a = new ClassA();
        System.out.println(a.methodB());
        a.methodA();
        methodC(a);
        System.out.println(a.methodB());
    }

}

