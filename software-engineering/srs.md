# Introduction

## Purpose
The purpose of this program is to ease the development of multi-version programs. 
That is, java programs targeting multiple versions of a dependency library.
This is especially useful for things like minecraft mods where the dev wants to target multiple versions of minecraft.

## Intended Audience
This program is intended for java developers who want to target multiple versions of a dependency library.

## Intended Use
This program is intended to be used as a gradle plugin.

## Scope
This program will create a gradle project with subprojects for each individual version, and a main project that
merges all the versioned dependencies together with package remapping.

## Definitions, Acronyms, and Abbreviations

- **Stub**: a replacement for a method in order to duplicate functionality on a different version
- **Remapping**: the process of changing the package of a class to a different package

# Overall Description

## User Needs
The user needs to be able to wholly develop a multi-version program using the joint gradle project, tho class overrides in the
subprojects for more messy code should be an option. Things like running the application could be handled by the subprojects,
with remapping handled by a pre-run task to compile the code into the versioned code needed to run in the subproject.

## Assumptions and Dependencies
The user will have a basic understanding of gradle and java. 
ASM will be used for bytecode manipulation and there will be an option for a "stub" to be created directly with asm (possibly at runtime)

## Constraints
The program must be capable of creating code compatible with java 8, and must be able to run on java 8.

# System Features and Requirements

## Functional Requirements
- The program must be able to create a gradle project with subprojects for each version
- The program must be able to merge the subproject's versioned dependencies into the main project
- The program must be capable of creating stubs for fields, methods or classes that do not exist in a version
- The program must be capable of remapping packages
- The program must be capable of creating a "stub" class or method with asm
- The program must be able to run the application in the subprojects
- The program must not require any runtime dependencies after remapping
- The program must be able to generate classes that run on java 8

## External Interface Requirements
- The program must be configurable from build.gradle
- The program must provide a way to specify the dependencies to target for merging

# Nonfunctional Requirements
