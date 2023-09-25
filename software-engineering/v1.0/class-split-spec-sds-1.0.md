# Introduction

## Purpose
This is a specification for how class splitting should work for the purpose of multi-version programs.
This document goes over the technical details of how a class will be split/transformed into a version that is
capable of running against a target version of the versioned dependencies.

## Intended Audience
This document is intended for developers who want to understand how class splitting works.
As well as those wanting to reimplement or extend the class splitting process.

## Intended Use
The classes outputted will be useable by the original versioned dependencies, and will be able to run on the target version.

## Scope
This document covers the technical details of how class splitting works, and how the split classes will be used.

## Definitions, Acronyms, and Abbreviations
//TODO

# Design

The classes must be splittable into versions that don't refer to members that don't exist in the target version, or to the merged class.
to this end, the user will be able to specify several types of helpers and annotations to be used in the split process.

## @Remove(versions = {})
This annotation will be used to specify that a method/field/class should be removed from the versioned class if the target version is in the list of versions.

## @Stub(versions = {}, ref = @Ref, field = false)
This annotation will be used to specify that a method/field/class should be replaced with a stub if the target version is in the list of versions.
the ref annotation will be used to specify the method/field/class to use as a reference for the stub.
if this is on a method, the method must be static and have a compatible signature with the method being stubbed.

if `@Ref` isn't provided, it will be attempted to be auto-determined by the stub based on it's name/desc.
the first arg will be used as the class. this means it must be provided when stubbing a static method.

## @Replace(versions = {}, ref = @Ref, field = false)
This annotation is similar to stub, but instead of replacing the method/field/class with a stub, it will allow for a more complicated replacement by passing an asm context and
running the function in order to produce the replacement at splitting time.

## @Ref
```java
public @interface Ref {

    Class<?> value();

    String member() default ""; // method/field name, if not provided these will attempt to be auto-determined by the stub field/method's name/desc

    String desc() default ""; // method/field desc

}
```

## @Versioned(versions = {})
This annotation is the inverse of @Remove, and will be used to specify that a method/field/class should only be included in the versioned class if the target version is in the list of versions.

## branch detection
In order to allow for versioned code to be used in split classes easier, methods can use 
a class `xyz.wagyourtail.multiversion.injected.CurrentVersion` with method `getCurrentVersion()` this can then be stripped out of the writer and replace with a ldc instead.
this *could* be done with @Stub's on the method, but that is lazy.

### difficulty
analysis of methods that use this requires checking for dead branches.
this is difficult (but not impossible). implementation may not throw warnings in methods that use this.