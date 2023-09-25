package com.example;

public class ClassC extends ClassB {

    public ClassC(String arg0) {
        super(arg0);
        throw new AssertionError();
    }
}
