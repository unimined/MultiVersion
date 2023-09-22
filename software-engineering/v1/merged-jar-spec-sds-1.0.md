# Introduction

## Purpose
This is a specification for how jar merging should work for the purpose of multi-version programs.
This document goes over the details of how class files will be merged for the purpose of creating the multi-versioned
copies of dependnecies.

## Intended Audience
This document is intended for developers who want to understand how class merging works.
As well as those wanting to reimplement or extend the class merging process.

## Intended Use
The classes outputted will be usable for development of multi-version programs as well as being parsable for the
class splitter when the classes are split. (see [class-split-spec-sds-1.0.md](class-split-spec-sds-1.0.md))

## Scope
This document covers the technical details of how class merging works, and how the merged classes will be used.

## Definitions, Acronyms, and Abbreviations
//TODO

# Design

## Notating merged classes
Merged classes will have their package prepended with `merged_v` and the version of the dependency they are merged from.
For example, a class merged from version 1.0.0 of a dependency would have the package `merged_v1_0_0.com.example.ClassName`
Sanatization of the version must be done to make it a valid package name, all bad characters should be replaced with `_`.
Classes will also be annotated by the class file visible annotation `@MergedClass` with the version of the dependency.

for example.
the class `com.example.ClassA`
with version 1.0 could look like
```java
package com.example;

public class ClassA {
    public void methodA() {
        System.out.println("Hello World!");
    }
}
```
and version 1.1 could look like
```java
package com.example;

public class ClassA {
    public void methodA() {
        System.out.println("Hello World!");
    }
    public void methodB() {
        System.out.println("Hello World!");
    }
}
```
the merged class would look like
```java
package merged.com.example;

@MergedClass("1.0")
public class ClassA {
    public void methodA() {
        System.out.println("Hello World!");
    }
    public void methodB() {
        System.out.println("Hello World!");
    }
}
```