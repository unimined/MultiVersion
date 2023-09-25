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

## Merged classes
There will be a merged class generated for the purpose of targetting instead of the versioned classes. 
This class will be generated in the package `merged` and will be named the same as the original class.
these classes will also be annotated by the class file visible annotation `@MergedClass` with the version of the dependency.
there will also be annotations for methods/fields in order to specify which versions they are from.

### Access changes
classes/members will only be considered for merged if they are public or protected. this means if a method is private on one version,
it won't appear to exist on the merged class for that version. the access will also be notated in a field on the member annotation.

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

@MergedClass(versions = {"1.0", "1.1"})
public class ClassA {
    
    @MergedMember(versions = {"1.0", "1.1"})
    public void methodA() {
        throw new AssertionError();
    }
    
    @MergedMember(versions = {"1.1"})
    public void methodB() {
        throw new AssertionError();
    }
}
```

## Hierarchy changes
in order to facilitate hierarchy changes, when these are detected, a few extra methods will be added to the merged class
the class will no longer inherit from the original merged class, and instead will add a method for "casting" to each version's parent that it adds.

this will also use an annotation parameter to notate the method as "synthetic", meaning it's not actually in the original class.

hierarchy changes will also be noted in the `@MergedClass` annotation with an `@Inheritance` annotation that denotes changes from what the merged class's inheritance would be.

for example.
the class `com.example.ClassA`
with version 1.0 could look like
```java
package com.example;

import com.example.ClassB;

public class ClassA extends ClassB {
    public void methodA() {
        System.out.println("Hello World!");
    }
}
```
and version 1.1 could look like
```java
package com.example;

import com.example.ClassC;

public class ClassA extends ClassC {
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

import merged.com.example.ClassB;
import merged.com.example.ClassC;

@MergedClass(versions = {"1.0", "1.1"}, inheritance = { @Inheritance(version = "1.0", superClass = ClassB.class), @Inheritance(version = "1.1", superClass = ClassC.class)})
public class ClassA {

    @MergedMember(versions = {"1.0", "1.1"})
    public void methodA() {
        throw new AssertionError();
    }

    @MergedMember(versions = {"1.1"})
    public void methodB() {
        throw new AssertionError();
    }

    @MergedMember(versions = {"1.0"}, synthetic = true)
    public ClassB mv$castTo$ClassB() {
        throw new AssertionError();
    }

    @MergedMember(versions = {"1.1"}, synthetic = true)
    public ClassC mv$castTo$ClassC() {
        throw new AssertionError();
    }
}
```

## Method/Field name/desc conflicts
in the case of a method or field having the same name and descriptor, the method/field will be renamed to include the version it is from.
this will also include the original name in the annotation.

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
    public int methodA() {
        return 1;
    }
}
```

the merged class would look like
```java
package merged.com.example;

@MergedClass(versions = {"1.0", "1.1"})
public class ClassA {
    
    @MergedMember(name = "methodA", versions = {"1.0"})
    public void methodA$mv$1_0() {
        throw new AssertionError();
    }
    
    @MergedMember(name = "methodA", versions = {"1.1"})
    public int methodA$mv$1_1() {
        throw new AssertionError();
    }
}
```

## determining the current version
A synthetic class will be inserted in dev to allow for code that needs to know the current version to build for.
this class will be named `CurrentVersion` and will be in the package `xyz.wagyourtail.jarmerger.injected`.
this class will have a static method `getCurrentVersion()` that returns a string of the current version.
this class will be removed in production builds, as this should be handled by the class splitter.

## Splitting versions (optional)
Split classes will have their package prepended with `v` and the version of the dependency they are merged from.
For example, a class merged from version 1.0.0 of a dependency would have the package `v1_0_0.com.example.ClassName`
Sanatization of the version must be done to make it a valid package name, all bad characters should be replaced with `_`.

### Accessing versioned versions
If split classes are enabled, the merged class will add syntetic methods (much like the hierarchy changes) to allow for accessing the versioned classes.
these methods will be named `mv$getVersionedClass$X_Y_Z()` where `X_Y_Z` is the version of the dependency.
