package com.example;

public class ClassB extends ClassA {

    public ClassB(String arg0) {
        System.out.println(arg0);
        System.out.println("ClassB");
    }

    public ClassB() {
        System.out.println("ClassB");
    }

    public int conflict() {
        return 1;
    }

}

