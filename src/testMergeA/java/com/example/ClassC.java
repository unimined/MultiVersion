package com.example;

public class ClassC extends ClassB {

    private String fieldA;

    public ClassC(String arg0) {
        super(arg0);
        throw new AssertionError();
    }
}
